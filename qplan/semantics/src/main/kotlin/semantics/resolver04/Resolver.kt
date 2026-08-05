package semantics.resolver04

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.SelectionForest
import model.Value
import model.registry.Resolver
import model.registry.availableDemand
import model.registry.successorDemand
import model.selectionForestOf
import semantics.correctresolution.argumentsContainErrorValue
import semantics.correctresolution.concreteObjectKey
import semantics.instantiateVariables
import semantics.materialize
import semantics.readVariable
import semantics.variables

/**
 * Returns the result for [selections] and all transitive resolver demand on this concrete object.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object =
    context(ResolutionSources()) {
        resolve(
            selections = selections,
            resolved = EngineResult.Object.of(type, emptyMap()),
        )
    }

/** [envelope] is the fixed symbolic demand visible before provider values are known. */
context(world: Assumptions, sources: ResolutionSources)
internal fun Value.Object.resolve(
    selections: SelectionForest,
    resolved: EngineResult.Object,
    envelope: SelectionForest = selectionForestOf(),
): EngineResult.Object {
    require(resolved.type == type) {
        "Initial result type ${resolved.type.typeName} does not match $type"
    }

    val applicableSelections =
        selections.filter { selection -> type in selection.possibleTypes }
    val resolverInputFragments =
        applicableSelections
            .groupBy { selection -> selection.concreteObjectKey(type) }
            .keys
            .mapNotNull { key ->
                if (
                    key.arguments.argumentsContainErrorValue() ||
                    key.field !in world.executorRegistry
                ) {
                    null
                } else {
                    val resolver = world.executorRegistry.resolver(key.field)
                    resolver.predecessorDemand(key.arguments)
                }
            }
    val symbolicInputDemand =
        resolverInputFragments.fold(selectionForestOf()) { demand, fragment ->
            demand + fragment.subselections
        }
    val symbolicSelections = applicableSelections + symbolicInputDemand
    // Substitute variables defined on this concrete object type across every rooted path.
    val currentVariables =
        symbolicSelections
            .variables()
            .filterTo(linkedSetOf()) { variable ->
                world.executorRegistry
                    .variableCoordinate(variable)
                    .field
                    .containingType == type
            }
    val resolvedVariables =
        resolveVariables(
            variables = currentVariables,
            resolved = resolved,
            envelope =
                envelope +
                    applicableSelections.successorDemand() +
                    symbolicInputDemand,
        )
    // Identity bindings retain descendant variables while current-occurrence variables are replaced.
    val currentBindings =
        symbolicSelections
            .variables()
            .associateWith { variable -> variable } +
            resolvedVariables.variableValues
    val concreteSelections =
        Fragment.of(type, applicableSelections)
            .instantiateVariables(currentBindings)
            .subselections
    val concreteInputDemand =
        resolverInputFragments.fold(selectionForestOf()) { demand, fragment ->
            demand +
                fragment
                    .instantiateVariables(currentBindings)
                    .subselections
        }
    val selectionsByKey =
        (concreteSelections + concreteInputDemand)
            .groupBy { selection -> selection.concreteObjectKey(type) }
    val keysToWiden = selectionsByKey.keys intersect resolvedVariables.keys
    val widened =
        keysToWiden.fold(resolvedVariables) { result, key ->
            sources.union(
                result,
                result.resolveExistingKey(
                    key = key,
                    fieldSelections = selectionsByKey.getValue(key),
                    coverage = envelope.coverageFor(key),
                ),
            )
        }
    val orderedKeys = dependencyOrder(selectionsByKey.keys - widened.keys, widened)
    return orderedKeys.fold(widened) { result, key ->
        sources.union(
            result,
            resolveKey(
                key = key,
                fieldSelections = selectionsByKey.getValue(key),
                resolved = result,
                envelope = envelope,
            ),
        )
    }.also { result -> sources.remember(result, this) }
}

context(world: Assumptions, sources: ResolutionSources)
private fun Value.Object.resolveVariables(
    variables: Set<Value.Variable>,
    resolved: EngineResult.Object,
    envelope: SelectionForest,
): EngineResult.Object =
    variables.fold(resolved) { result, variable ->
        if (variable in result.variableValues) {
            result
        } else {
            val provider = world.executorRegistry.variable(variable)
            val withDependencies =
                resolveVariables(
                    variables = provider.variables(),
                    resolved = result,
                    envelope = envelope,
                )
            val instantiated = provider.instantiateVariables(withDependencies.variableValues)
            val withProvider =
                resolve(
                    selections = selectionForestOf(instantiated),
                    resolved = withDependencies,
                    envelope = envelope,
                )
            val value = withProvider.readVariable(instantiated)
            sources.union(
                withProvider,
                EngineResult.Object.of(
                    type = type,
                    cells = emptyMap(),
                    variableValues = mapOf(variable to value),
                ),
            )
        }
    }

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
private fun Value.Object.dependencyOrder(
    keys: Set<Value.Key>,
    resolved: EngineResult.Object,
    ordered: List<Value.Key> = emptyList(),
): List<Value.Key> {
    if (keys.isEmpty()) return ordered

    val ready =
        keys.filter { key ->
            dependenciesOf(key, keys, resolved).isEmpty()
        }.toSet()
    require(ready.isNotEmpty()) {
        "Resolver dependencies on ${type.typeName} contain a cycle"
    }
    return dependencyOrder(
        keys = keys - ready,
        resolved = resolved,
        ordered = ordered + ready,
    )
}

/**
 * Returns the unresolved sibling keys demanded by the field resolver for [consumer].
 */
context(world: Assumptions)
private fun Value.Object.dependenciesOf(
    consumer: Value.Key,
    unresolved: Set<Value.Key>,
    resolved: EngineResult.Object,
): Set<Value.Key> {
    if (
        consumer.arguments.argumentsContainErrorValue() ||
        consumer.field !in world.executorRegistry
    ) {
        return emptySet()
    }

    val selections =
        world.executorRegistry
            .resolver(consumer.field)
            .objectFragment(consumer.arguments)
            .instantiateVariables(resolved.variableValues)
            .subselections
    return unresolved
        .filter { sibling ->
            sibling != consumer &&
                !selections.all { selection ->
                    type !in selection.possibleTypes ||
                        selection.concreteObjectKey(type) != sibling
                }
        }.toSet()
}

/**
 * Returns a one-cell object result for [key] and its merged [fieldSelections].
 */
context(world: Assumptions, sources: ResolutionSources)
private fun Value.Object.resolveKey(
    key: Value.Key,
    fieldSelections: SelectionForest,
    resolved: EngineResult.Object,
    envelope: SelectionForest,
): EngineResult.Object {
    val cell =
        if (key.arguments.argumentsContainErrorValue()) {
            EngineResult.Cell.Error
        } else {
            val subselections =
                fieldSelections.flatMap { selection -> selection.subselections }
            val availableCoverage =
                envelope
                    .coverageFor(key)
                    .flatMap { selection -> selection.subselections }
            val fieldOutput =
                when {
                    key.field.fieldName == "__typename" ->
                        Value.String.of(type.typeName).let { value ->
                            Resolver.OutputProjection(value, value)
                        }

                    key.field in world.executorRegistry -> {
                        val resolver = world.executorRegistry.resolver(key.field)
                        val objectFragment =
                            resolver
                                .objectFragment(key.arguments)
                                .instantiateVariables(resolved.variableValues)
                        // The predecessor demand and dependency order put the complete input here.
                        val input = resolved.materialize(objectFragment)
                        resolver.resolveWithSource(
                            input = input,
                            arguments = key.arguments,
                            selections = subselections.successorDemand(),
                            speculativeDemand = availableCoverage,
                        )
                    }

                    else -> {
                        // The producing resolver supplies demanded output-selection fields.
                        fieldValues.getValue(key).let { value ->
                            Resolver.OutputProjection(value, value)
                        }
                    }
                }
            val availableSubselections =
                fieldOutput.projected.availableDemand(availableCoverage)
            EngineResult.Cell.of(
                value =
                    fieldOutput.projected.resolveValue(
                        selections = subselections,
                        envelope = availableSubselections,
                        source = fieldOutput.source,
                    ),
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}

/**
 * Returns this nullable resolver output resolved for [selections] throughout its value tree.
 */
context(world: Assumptions, sources: ResolutionSources)
private fun Value.Output?.resolveValue(
    selections: SelectionForest,
    envelope: SelectionForest = selectionForestOf(),
    source: Value.Output? = this,
): EngineResult? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> {
            require(selections.isEmpty() && envelope.isEmpty())
            this
        }

        is Value.Object ->
            (source as? Value.Object ?: this).resolve(
                selections,
                EngineResult.Object.of(type, emptyMap()),
                envelope,
            )

        is Value.OutputList -> {
            val sourceValues =
                (source as? Value.OutputList)
                    ?.takeIf { it.values.size == values.size }
                    ?.values
            EngineResult.List.of(
                typeExpr = typeExpr,
                cells =
                    values.mapIndexed { index, value ->
                        EngineResult.Cell.of(
                            value =
                                value.resolveValue(
                                    selections,
                                    envelope,
                                    sourceValues?.get(index) ?: value,
                                ),
                        )
                    },
            )
        }
    }
