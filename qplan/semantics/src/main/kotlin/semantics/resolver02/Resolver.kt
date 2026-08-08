package semantics.resolver02

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
 * Resolves [selections] with non-selective resolver applications. Results may contain more OER
 * nodes than are strictly necessary to resolve the query.
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

    val closedDemand = type.closeResolverDemand(path, selections)
    val unresolvedKeys =
        closedDemand
            .keys()
            .filter { key ->
                key !in resolved.keys ||
                    !world.behavioral(key.field)
            }.toSet()
    val orderedKeys = dependencyOrder(path, unresolvedKeys)
    return orderedKeys.fold(resolved) { result, key ->
        val selection = closedDemand[key]
        result.union(resolveKey(path, selection, result))
    }
}

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
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

/**
 * Returns the unresolved sibling keys demanded by the field resolver for [consumer].
 */
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

/**
 * Returns a one-cell object result for the merged [fieldSelection].
 */
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
                                .stampedObjectFragment(key.arguments, path + key)
                                .mergeToGround(type)
                        // Closure and dependency order put the complete input in this prefix.
                        val input = resolved.materialize(objectFragment)
                        resolver(
                            input = input,
                            arguments = key.arguments,
                        )
                    }

                    else -> {
                        // The producing resolver supplies demanded output-selection fields.
                        fieldValues.getValue(key)
                    }
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
