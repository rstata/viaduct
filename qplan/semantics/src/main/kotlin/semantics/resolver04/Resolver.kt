package semantics.resolver04

import model.Assumptions
import model.EngineResult
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.selectionForestOf
import model.union
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
    resolve(
        selections = selections,
        resolved = EngineResult.Object.of(type, emptyMap()),
    )

context(world: Assumptions)
private fun Value.Object.resolve(
    selections: SelectionForest,
    resolved: EngineResult.Object,
): EngineResult.Object {
    require(resolved.type == type) {
        "Initial result type ${resolved.type.typeName} does not match $type"
    }

    val closure = closeResolverDemand(selections, resolved)
    val selectionsByKey =
        closure.selections.groupBy { selection -> selection.concreteObjectKey(type) }
    val orderedKeys = dependencyOrder(selectionsByKey.keys - closure.resolved.keys, closure.resolved)
    return orderedKeys.fold(closure.resolved) { result, key ->
        result.union(resolveKey(key, selectionsByKey.getValue(key), result))
    }
}

/**
 * Returns the applicable demand, including all transitive resolver demand on this concrete object.
 */
context(world: Assumptions)
private fun Value.Object.closeResolverDemand(
    selections: SelectionForest,
    resolved: EngineResult.Object,
    expanded: Set<Value.Key> = emptySet(),
): DemandClosure {
    val applicableSelections =
        selections.filter { selection -> type in selection.possibleTypes }
    val groupedSelections =
        applicableSelections.groupBy { selection -> selection.concreteObjectKey(type) }
    if (world.noTransitiveDemand && expanded.isNotEmpty()) {
        return DemandClosure(applicableSelections, resolved)
    }
    val unexpandedResolverKeys =
        groupedSelections.keys.filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in world.executorRegistry
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) {
        return DemandClosure(applicableSelections, resolved)
    }

    val (resolvedVariables, resolverDemand) =
        unexpandedResolverKeys.fold(resolved to selectionForestOf()) { (result, demand), key ->
            val fragment =
                world.executorRegistry
                    .resolver(key.field)
                    .objectFragment(key.arguments)
            val withVariables = resolveVariables(fragment.variables(), result)
            val instantiated = fragment.instantiateVariables(withVariables.variableValues)
            withVariables to (demand + instantiated.subselections)
        }
    return closeResolverDemand(
        selections = applicableSelections + resolverDemand,
        resolved = resolvedVariables,
        expanded = expanded + unexpandedResolverKeys,
    )
}

private data class DemandClosure(
    val selections: SelectionForest,
    val resolved: EngineResult.Object,
)

context(world: Assumptions)
private fun Value.Object.resolveVariables(
    variables: Set<Value.Variable>,
    resolved: EngineResult.Object,
): EngineResult.Object =
    variables.fold(resolved) { result, variable ->
        if (variable in result.variableValues) {
            result
        } else {
            val provider = world.executorRegistry.variable(variable)
            val withDependencies = resolveVariables(provider.variables(), result)
            val instantiated = provider.instantiateVariables(withDependencies.variableValues)
            val withProvider =
                resolve(
                    selections = selectionForestOf(instantiated),
                    resolved = withDependencies,
                )
            val value = withProvider.readVariable(instantiated)
            withProvider.union(
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
    return unresolved.filter { sibling ->
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
context(world: Assumptions)
private fun Value.Object.resolveKey(
    key: Value.Key,
    fieldSelections: SelectionForest,
    resolved: EngineResult.Object,
): EngineResult.Object {
    val cell =
        if (key.arguments.argumentsContainErrorValue()) {
            EngineResult.Cell.Error
        } else {
            val subselections =
                fieldSelections.flatMap { selection -> selection.subselections }
            val fieldValue =
                when {
                    key.field.fieldName == "__typename" ->
                        Value.String.of(type.typeName)

                    key.field in world.executorRegistry -> {
                        val resolver = world.executorRegistry.resolver(key.field)
                        val objectFragment =
                            resolver
                                .objectFragment(key.arguments)
                                .instantiateVariables(resolved.variableValues)
                        // Closure and dependency order put the complete input in this prefix.
                        val input = resolved.materialize(objectFragment)
                        resolver.resolve(
                            input = input,
                            arguments = key.arguments,
                            transitiveDemand = subselections.withExtendedResolverDemand(),
                        )
                    }

                    else -> {
                        // The producing resolver supplies demanded output-selection fields.
                        fieldValues.getValue(key)
                    }
                }
            EngineResult.Cell.of(
                value = fieldValue.resolveValue(subselections),
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}

/**
 * Returns this nullable resolver output resolved for [selections] throughout its value tree.
 */
context(world: Assumptions)
private fun Value.Output?.resolveValue(selections: SelectionForest): EngineResult? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this

        is Value.Object -> resolve(selections)

        is Value.OutputList ->
            EngineResult.List.of(
                typeExpr = typeExpr,
                cells =
                    values.map { value ->
                        EngineResult.Cell.of(
                            value = value.resolveValue(selections),
                        )
                    },
            )
    }

/**
 * Adds the precomputed input requirements of every resolver occurrence in this demand.
 *
 * Each resolver's extended fragment is rooted at its occurrence's containing object. Recursing
 * through subselections retains the path and type guards that locate nested occurrences.
 */
context(world: Assumptions)
private fun SelectionForest.withExtendedResolverDemand(): SelectionForest =
    flatMap { selection ->
        val nestedDemand = selection.subselections.withExtendedResolverDemand()
        val rootedSelection =
            Selection.of(
                key = selection.key,
                nominalType = selection.nominalType,
                possibleTypes = selection.possibleTypes,
                subselections = nestedDemand,
            )
        val resolverDemand =
            selection.possibleTypes.fold(selectionForestOf()) { demand, possibleType ->
                val key = selection.concreteObjectKey(possibleType)
                if (
                    key.arguments.argumentsContainErrorValue() ||
                    key.field !in world.executorRegistry
                ) {
                    demand
                } else {
                    val resolver = world.executorRegistry.resolver(key.field)
                    val fragment =
                        if (world.noTransitiveDemand) {
                            resolver.objectFragment(key.arguments)
                        } else {
                            resolver.extendedFragment(key.arguments)
                        }
                    demand + fragment.subselections
                }
            }
        selectionForestOf(rootedSelection) + resolverDemand
    }
