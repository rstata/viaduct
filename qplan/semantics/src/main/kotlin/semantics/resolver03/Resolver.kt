package semantics.resolver03

import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.SelectionForest
import model.Value
import model.merge
import model.registry.demandsFromSibling
import model.registry.successorDemand
import model.selectionForestOf
import model.union
import semantics.correctresolution.argumentsContainErrorValue
import semantics.materialize
import semantics.resolvePaths
import semantics.resolveValue

/**
 * Resolves [selections] when resolver object fragments may be nonempty but contain no variables.
 * Results are selective relative to Resolver02; whether they contain only the necessary OER nodes
 * has not been proved.
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

    val applicableSelections = selections.merge(type)
    val resolverInputDemand =
        applicableSelections
            .keys()
            .fold(selectionForestOf()) { demand, key ->
                if (
                    key.arguments.argumentsContainErrorValue() ||
                    key.field !in world.resolverRegistry
                ) {
                    demand
                } else {
                    val resolver = world.resolverRegistry.resolver(key.field)
                    val fragment = resolver.predecessorDemand(key.arguments)
                    demand + fragment.subselections
                }
            }
    val mergedSelections =
        (applicableSelections + resolverInputDemand)
            .merge(type)
    val orderedKeys = dependencyOrder(mergedSelections.keys() - resolved.keys)
    return orderedKeys.fold(resolved) { result, key ->
        val selection = mergedSelections[key]
        result.union(resolveKey(selection, result))
    }
}

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
private fun Value.Object.dependencyOrder(
    keys: Set<Value.ObjectKey>,
    ordered: List<Value.ObjectKey> = emptyList(),
): List<Value.ObjectKey> {
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
    consumer: Value.ObjectKey,
    unresolved: Set<Value.ObjectKey>,
): Set<Value.ObjectKey> {
    if (
        consumer.arguments.argumentsContainErrorValue() ||
        consumer.field !in world.resolverRegistry
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
 * Returns a one-cell object result for the merged [fieldSelection].
 */
context(world: Assumptions)
private fun Value.Object.resolveKey(
    fieldSelection: ObjectSelection,
    resolved: EngineResult.Object,
): EngineResult.Object {
    val key = fieldSelection.key
    val cell =
        if (key.arguments.argumentsContainErrorValue()) {
            EngineResult.Cell.Error
        } else {
            val subselections = fieldSelection.subselections
            val resolutionSelections =
                if (key.field in world.resolverRegistry) {
                    subselections.successorDemand()
                } else {
                    subselections
                }
            val fieldValue =
                when {
                    key.field.fieldName == "__typename" ->
                        Value.String.of(type.typeName)

                    key.field in world.resolverRegistry -> {
                        val resolver = world.resolverRegistry.resolver(key.field)
                        val objectFragment = resolver.objectFragment(key.arguments)
                        // The predecessor demand and dependency order put the complete input here.
                        val input = resolved.materialize(objectFragment)
                        resolver(
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
