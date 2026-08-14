package semantics

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.PathComponent
import model.SelectionForest
import model.Value
import model.groundKey
import model.registry.demandsFromSibling
import semantics.correctresolution.argumentsContainErrorValue

internal data class SelectionCompletion(
    val selections: SelectionForest,
    val retainCompleteOutput: Boolean = false,
)

/**
 * Resolves [selections] at this exact object occurrence, extending [resolved].
 *
 * The fixed local-demand closure and dependency-first fold are shared by Resolver01-03.
 * [runtimeSupport] supplies their differing output-boundary semantics.
 */
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal fun Value.Object.orchestrateKeys(
    path: List<PathComponent>,
    selections: SelectionForest,
    resolved: EngineResult.Object,
): EngineResult.Object {
    require(resolved.type == type) {
        "Initial result type ${resolved.type.typeName} does not match $type"
    }

    val closedDemand = type.closeResolverDemand(path, selections)
    val unresolvedKeys = closedDemand.groundKeys() - resolved.keys
    val orderedKeys = dependencyOrder(path, unresolvedKeys)
    orderedKeys.forEach { key ->
        val selection = closedDemand[key]
        resolveKey(path, selection, resolved)
            ?.resolveObjects { objectResolution ->
                objectResolution.source.orchestrateKeys(
                    path = objectResolution.path,
                    selections = objectResolution.selections,
                    resolved = objectResolution.target,
                )
            }
    }
    return resolved
}

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
internal fun Value.Object.dependencyOrder(
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

/**
 * Resolves and sets the cell value and access result for [fieldSelection], yielding its passive
 * result-tree fringe.
 */
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal fun Value.Object.resolveKey(
    path: List<PathComponent>,
    fieldSelection: ObjectSelection,
    resolved: EngineResult.Object,
): ResolvedValue? {
    val key = fieldSelection.groundKey()
    val cell = resolved.reserveCell(key)
    return when {
        key.arguments.argumentsContainErrorValue() -> {
            cell.setValue(Value.Error)
            cell.setAccessAccepted(Value.Error)
            null
        }

        key.field.fieldName == "__typename" -> {
            cell.setValue(Value.String.of(type.typeName))
            cell.setAccessAccepted(Value.Boolean.of(true))
            null
        }

        else -> {
            val completion = runtimeSupport.complete(fieldSelection.subselections)
            val resolutionSelections = completion.selections
            val fieldValue =
                if (key.field in world.resolverRegistry) {
                    val resolver = world.resolverRegistry.resolver(key.field)
                    val objectFragment =
                        resolver
                            .objectFragmentAt(path + key)
                    val input =
                        runBlocking {
                            resolved.materialize(
                                selections = objectFragment,
                                reader = path + key,
                            )
                        }
                    if (completion.retainCompleteOutput) {
                        resolver.completeOutput(
                            input = input,
                            arguments = key.arguments,
                            selections = resolutionSelections,
                        )
                    } else if (world.selectiveResolvers) {
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
                    require(!world.selectiveResolvers) {
                        "Passive key found ($key)."
                    }
                    fieldValues.getValue(key)
                }
            val resolvedValue =
                fieldValue.resolveValue(
                    path = path + key,
                    resolverDemand = resolutionSelections,
                    retainCompleteOutput = completion.retainCompleteOutput,
                )
            cell.setValue(resolvedValue.engineResult)
            cell.setAccessAccepted(Value.Boolean.of(true))
            resolvedValue
        }
    }
}
