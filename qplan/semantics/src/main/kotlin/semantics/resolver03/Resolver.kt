package semantics.resolver03

import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.PathComponent
import model.SelectionForest
import model.Value
import model.merge
import model.registry.demandsFromSibling
import model.registry.successorDemand
import model.selectionForestOf
import model.union
import semantics.bindFromArguments
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
        path = emptyList(),
        selections = selections,
        resolved = EngineResult.Object.of(type, emptyMap()),
    )

context(world: Assumptions)
private fun Value.Object.resolve(
    path: List<PathComponent>,
    selections: SelectionForest,
    resolved: EngineResult.Object,
): EngineResult.Object {
    require(resolved.type == type) {
        "Initial result type ${resolved.type.typeName} does not match $type"
    }

    val applicableSelections = selections.merge(type)
    applicableSelections.keys().bindFromArguments(path)
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
                    demand + resolver.infusedPredecessorDemand(key.arguments, path + key)
                }
            }
    val mergedSelections =
        (applicableSelections + resolverInputDemand)
            .merge(type)
    val unresolvedKeys = mergedSelections.keys() - resolved.keys
    val orderedKeys = dependencyOrder(path, unresolvedKeys)
    return orderedKeys.fold(resolved) { result, key ->
        val selection = mergedSelections[key]
        result.union(resolveKey(path, selection, result))
    }
}

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
private fun Value.Object.dependencyOrder(
    path: List<PathComponent>,
    keys: Set<Value.ObjectKey>,
    ordered: List<Value.ObjectKey> = emptyList(),
): List<Value.ObjectKey> {
    if (keys.isEmpty()) return ordered

    val ready =
        keys.filter { key ->
            dependenciesOf(path, key, keys).isEmpty()
        }.toSet()
    require(ready.isNotEmpty()) {
        "Resolver dependencies on ${type.typeName} contain a cycle"
    }
    return dependencyOrder(
        path = path,
        keys = keys - ready,
        ordered = ordered + ready,
    )
}

/**
 * Returns the unresolved sibling keys demanded by the field resolver for [consumer].
 */
context(world: Assumptions)
private fun Value.Object.dependenciesOf(
    path: List<PathComponent>,
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
                consumer.demandsFromSibling(sibling, path + consumer)
        }.toSet()
}

/**
 * Returns a one-cell object result for the merged [fieldSelection].
 */
context(world: Assumptions)
private fun Value.Object.resolveKey(
    path: List<PathComponent>,
    fieldSelection: ObjectSelection,
    resolved: EngineResult.Object,
): EngineResult.Object {
    val key = fieldSelection.key
    val cell =
        when {
            key.arguments.argumentsContainErrorValue() ->
                EngineResult.Cell.Error

            key.field.fieldName == "__typename" ->
                EngineResult.Cell.of(Value.String.of(type.typeName))

            else -> {
                require(key.field in world.resolverRegistry) {
                    "Passive key found ($key)."
                }

                // Resolution applies this producer before recursively visiting fields with resolvers
                // in its output. When those successor resolvers are reached, their predecessor
                // demand will be added to their containing OER's local demand, but that happens
                // after this producer's output has been selectively projected. successorDemand
                // anticipates that situation by adding the predecessor demand from those successor
                // resolvers to this producer's output demand, ensuring the passive values
		// needed to materialize each successor's input will be produced.
                val resolutionSelections = fieldSelection.subselections.successorDemand()

                val resolver = world.resolverRegistry.resolver(key.field)
                val objectFragment =
                    resolver
                        .stampedObjectFragment(key.arguments, path + key)
                        .merge(type)
                val fieldValue =
                    resolver(
                        // The predecessor demand added in [resolve] for this resolver, either
                        // directly or through another resolver's [predecessorDemand] closure,
                        // combined with dependency ordering of field resolution, ensures
                        // that the complete input needed by this resolver is already in the
                        // OER tree and can be materialized.
                        input = resolved.materialize(objectFragment),
                        arguments = key.arguments,
                        selections = resolutionSelections,
                    )
                val resolvedValue =
                    fieldValue.resolveValue(
                        path = path + key,
                        resolverDemand = resolutionSelections,
                        beSelective = true,
                    )
                EngineResult.Cell.of(
                    fieldValue.resolvePaths(
                        path = path + key,
                        resolvedValue = resolvedValue,
                    ) { objectPath, value, selections, resolved ->
                        value.resolve(objectPath, selections, resolved)
                    },
                )
            }
        }
    return EngineResult.Object.of(type, mapOf(key to cell))
}
