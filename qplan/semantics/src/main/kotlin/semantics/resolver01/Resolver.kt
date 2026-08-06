package semantics.resolver01

import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.Schema
import model.SelectionForest
import model.Value
import model.merge
import model.registry.demandsFromSibling
import model.selectionForestOf
import model.union
import semantics.correctresolution.argumentsContainErrorValue
import semantics.materialize
import semantics.resolvePaths
import semantics.resolveValue

/**
 * Resolves [selections] when resolver object fragments are empty, except for generated Node-loader
 * fragments that select synthetic `foo$id` or `foo$ids` bridge fields. Results are non-selective
 * and may contain more OER nodes than are strictly necessary to resolve the query.
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
    val closedDemand = type.closeResolverDemand(selections)
    val mergedSelections = closedDemand.merge(type)
    val orderedKeys = dependencyOrder(mergedSelections.keys() - resolved.keys)
    return orderedKeys.fold(resolved) { result, key ->
        val selection = mergedSelections[key]
        result.union(resolveKey(selection, result))
    }
}

context(world: Assumptions)
private fun Schema.ObjectType.closeResolverDemand(
    selections: SelectionForest,
    expanded: Set<Value.ObjectKey> = emptySet(),
): SelectionForest {
    val applicableSelections = selections.merge(this)
    val unexpandedResolverKeys =
        applicableSelections.keys().filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in world.resolverRegistry
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) return applicableSelections

    val resolverDemand =
        unexpandedResolverKeys.fold(selectionForestOf()) { demand, key ->
            demand +
                world.resolverRegistry
                    .resolver(key.field)
                    .objectFragment(key.arguments)
                    .subselections
        }
    return closeResolverDemand(
        selections = applicableSelections + resolverDemand,
        expanded = expanded + unexpandedResolverKeys,
    )
}

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
            val fieldValue =
                when {
                    key.field.fieldName == "__typename" ->
                        Value.String.of(type.typeName)

                    key.field in world.resolverRegistry -> {
                        val resolver = world.resolverRegistry.resolver(key.field)
                        val objectFragment = resolver.objectFragment(key.arguments)
                        resolver(
                            input = resolved.materialize(objectFragment),
                            arguments = key.arguments,
                        )
                    }

                    else -> fieldValues.getValue(key)
                }
            EngineResult.Cell.of(
                value =
                    fieldValue
                        .resolveValue(
                            resolverDemand = subselections,
                            selections = null,
                        ).let { resolvedValue ->
                            fieldValue.resolvePaths(resolvedValue) { value, selections, resolved ->
                                value.resolve(selections, resolved)
                            }
                        },
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}
