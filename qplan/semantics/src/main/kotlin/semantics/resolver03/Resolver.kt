package semantics.resolver03

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.registry.demandsFromSibling
import model.registry.successorDemand
import model.selectionForestOf
import model.union
import semantics.correctresolution.argumentsContainErrorValue
import semantics.correctresolution.concreteObjectKey
import semantics.materialize
import semantics.resolvePaths
import semantics.resolveValue

/**
 * Returns the result for [selections] and all transitive resolver demand on this concrete object.
 *
 * Every supplied or resolver-introduced selection applicable at an object visited by this
 * operation must contain no [Value.Variable] in its key arguments.
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

    val applicableSelections =
        selections.filter { selection -> type in selection.possibleTypes }
    val resolverInputDemand =
        applicableSelections
            .groupBy { selection -> selection.concreteObjectKey(type) }
            .keys
            .fold(selectionForestOf()) { demand, key ->
                if (
                    key.arguments.argumentsContainErrorValue() ||
                    key.field !in world.executorRegistry
                ) {
                    demand
                } else {
                    val resolver = world.executorRegistry.resolver(key.field)
                    val fragment = resolver.predecessorDemand(key.arguments)
                    demand + fragment.subselections
                }
            }
    val selectionsByKey =
        (applicableSelections + resolverInputDemand)
            .groupBy { selection -> selection.concreteObjectKey(type) }
    val orderedKeys = dependencyOrder(selectionsByKey.keys - resolved.keys)
    return orderedKeys.fold(resolved) { result, key ->
        result.union(resolveKey(key, selectionsByKey.getValue(key), result))
    }
}

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
private fun Value.Object.dependencyOrder(
    keys: Set<Value.Key>,
    ordered: List<Value.Key> = emptyList(),
): List<Value.Key> {
    if (keys.isEmpty()) return ordered

    val ready =
        keys.filter { key ->
            dependenciesOf(key, keys).isEmpty()
        }.toSet()
    require(ready.isNotEmpty()) {
        "Resolver dependencies on ${type.typeName} contain a cycle"
    }
    return dependencyOrder(
        keys = keys - ready,
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
): Set<Value.Key> {
    if (
        consumer.arguments.argumentsContainErrorValue() ||
        consumer.field !in world.executorRegistry
    ) {
        return emptySet()
    }

    return unresolved
        .filter { sibling ->
            sibling != consumer &&
                consumer.demandsFromSibling(sibling)
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
            val resolutionSelections =
                if (key.field in world.executorRegistry) {
                    subselections.successorDemand()
                } else {
                    subselections
                }
            val fieldValue =
                when {
                    key.field.fieldName == "__typename" ->
                        Value.String.of(type.typeName)

                    key.field in world.executorRegistry -> {
                        val resolver = world.executorRegistry.resolver(key.field)
                        val objectFragment = resolver.objectFragment(key.arguments)
                        // The predecessor demand and dependency order put the complete input here.
                        val input = resolved.materialize(objectFragment)
                        resolver.tenantResolve(
                            input = input,
                            arguments = key.arguments,
                            selections = resolutionSelections,
                        )
                    }

                    else -> {
                        // The producing resolver supplies demanded output-selection fields.
                        fieldValues.getValue(key)
                    }
                }
            EngineResult.Cell.of(
                value =
                    fieldValue.resolveValue(resolutionSelections).let { resolvedValue ->
                        fieldValue.resolvePaths(resolvedValue) { value, selections, resolved ->
                            value.resolve(selections, resolved)
                        }
                    },
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}
