package semantics

import model.Assumptions
import model.EngineResult
import model.GroundSelection
import model.PathComponent
import model.SelectionForest
import model.Value
import model.mergeToGround
import model.registry.demandsFromSibling
import model.union
import semantics.correctresolution.argumentsContainErrorValue

internal data class SelectionCompletion(
    val selections: SelectionForest,
    val selective: Boolean,
)

/**
 * Supplies the output-boundary policy for one resolution constructor.
 *
 * [complete] expands the selections visible at a resolver output boundary and indicates whether
 * the resolver result and passive traversal are selective to those completed selections.
 */
internal fun interface SelectionCompleter {
    context(world: Assumptions)
    fun complete(selections: SelectionForest): SelectionCompletion
}

/**
 * Resolves [selections] at this exact object occurrence, extending [resolved].
 *
 * The fixed local-demand closure and dependency-first fold are shared by Resolver01-03.
 * [selectionCompleter] supplies their differing output-boundary semantics.
 */
context(world: Assumptions, selectionCompleter: SelectionCompleter)
internal fun Value.Object.resolve(
    path: List<PathComponent>,
    selections: SelectionForest,
    resolved: EngineResult.Object,
): EngineResult.Object {
    require(resolved.type == type) {
        "Initial result type ${resolved.type.typeName} does not match $type"
    }

    val closedDemand = type.closeResolverDemand(path, selections)
    val unresolvedKeys = closedDemand.keys() - resolved.keys
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

/** Returns the unresolved sibling keys demanded by the field resolver for [consumer]. */
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

/** Returns a one-cell object result for the merged [fieldSelection]. */
context(world: Assumptions, selectionCompleter: SelectionCompleter)
private fun Value.Object.resolveKey(
    path: List<PathComponent>,
    fieldSelection: GroundSelection,
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
                val completion = selectionCompleter.complete(fieldSelection.subselections)
                val resolutionSelections = completion.selections
                val fieldValue =
                    if (key.field in world.resolverRegistry) {
                        val resolver = world.resolverRegistry.resolver(key.field)
                        val objectFragment =
                            resolver
                                .stampedObjectFragment(path + key)
                                .mergeToGround(type)
                        val input = resolved.materialize(objectFragment)
                        if (completion.selective) {
                            resolver(
                                input = input,
                                arguments = key.arguments,
                                selections = resolutionSelections,
                            )
                        } else {
                            resolver(
                                input = input,
                                arguments = key.arguments,
                            )
                        }
                    } else {
                        require(!completion.selective) {
                            "Passive key found ($key)."
                        }
                        fieldValues.getValue(key)
                    }
                val resolvedValue =
                    fieldValue.resolveValue(
                        path = path + key,
                        resolverDemand = resolutionSelections,
                        beSelective = completion.selective,
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
