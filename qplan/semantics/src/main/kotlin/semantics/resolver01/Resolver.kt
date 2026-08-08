package semantics.resolver01

import model.Assumptions
import model.EngineResult
import model.GroundSelection
import model.PathComponent
import model.SelectionForest
import model.Value
import model.mergeToGround
import model.registry.demandsFromSibling
import model.union
import semantics.closeResolverDemand
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
    val closedDemand = type.closeResolverDemand(path, selections)
    val unresolvedKeys = closedDemand.keys() - resolved.keys
    val orderedKeys = dependencyOrder(path, unresolvedKeys)
    return orderedKeys.fold(resolved) { result, key ->
        val selection = closedDemand[key]
        result.union(resolveKey(path, selection, result))
    }
}

context(world: Assumptions)
private fun Value.Object.dependencyOrder(
    path: List<PathComponent>,
    keys: Set<Value.GroundKey>,
    ordered: List<Value.GroundKey> = emptyList(),
): List<Value.GroundKey> {
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

context(world: Assumptions)
private fun Value.Object.dependenciesOf(
    path: List<PathComponent>,
    consumer: Value.GroundKey,
    unresolved: Set<Value.GroundKey>,
): Set<Value.GroundKey> {
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

context(world: Assumptions)
private fun Value.Object.resolveKey(
    path: List<PathComponent>,
    fieldSelection: GroundSelection,
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
                        val objectFragment =
                            resolver
                                .stampedObjectFragment(path + key)
                                .mergeToGround(type)
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
                            path = path + key,
                            resolverDemand = subselections,
                            beSelective = false,
                        ).let { resolvedValue ->
                            fieldValue.resolvePaths(
                                path = path + key,
                                resolvedValue = resolvedValue,
                            ) { objectPath, value, selections, resolved ->
                                value.resolve(objectPath, selections, resolved)
                            }
                        },
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}
