package semantics

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineErrorData
import model.EngineResult
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.outputType
import model.ObjectSelection
import model.Arguments
import model.PathComponent
import model.SelectionForest
import viaduct.engine.api.EngineObjectData
import model.groundKey
import model.outputValue
import model.registry.demandsFromSibling
import model.schemaType
import semantics.correctresolution.argumentsContainErrorValue

/**
 * Resolves [selections] at this exact object occurrence, extending [resolved].
 *
 * The fixed local-demand closure and dependency-first fold are shared by Resolver01-03.
 * [resolverSupport] supplies their differing output-boundary semantics.
 */
context(world: Assumptions, resolverSupport: ResolverSupport)
internal fun EngineObjectData.Sync.orchestrateKeys(
    path: List<PathComponent>,
    selections: SelectionForest,
    resolved: ObjectEngineResult,
): ObjectEngineResult {
    require(resolved.type == schemaType) {
        "Initial result type ${resolved.type.name} does not match $schemaType"
    }

    val closedDemand = schemaType.closeResolverDemand(path, selections)
    val unresolvedKeys = closedDemand.groundKeys() - resolved.keys
    val orderedKeys = dependencyOrder(path, unresolvedKeys)
    orderedKeys.forEach { key ->
        val selection = closedDemand[key]
        resolveKey(path, selection, resolved)
            ?.resolveRetainedObjects { passiveObjectOccurrence ->
                passiveObjectOccurrence.source.orchestrateKeys(
                    path = passiveObjectOccurrence.path,
                    selections = passiveObjectOccurrence.selections,
                    resolved = passiveObjectOccurrence.target,
                )
            }
    }
    return resolved
}

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
internal fun EngineObjectData.Sync.dependencyOrder(
    path: List<PathComponent>,
    keys: Set<ObjectEngineResult.GroundKey>,
    ordered: List<ObjectEngineResult.GroundKey> = emptyList(),
): List<ObjectEngineResult.GroundKey> {
    if (keys.isEmpty()) return ordered

    val ready =
        keys.filter { key ->
            dependenciesOf(path, key, keys).isEmpty()
        }.toSet()
    require(ready.isNotEmpty()) {
        "Resolver dependencies on ${schemaType.name} contain a cycle"
    }
    return dependencyOrder(
        path = path,
        keys = keys - ready,
        ordered = ordered + ready,
    )
}

/** Returns the unresolved sibling keys demanded by the field resolver for [consumer]. */
context(world: Assumptions)
private fun EngineObjectData.Sync.dependenciesOf(
    path: List<PathComponent>,
    consumer: ObjectEngineResult.GroundKey,
    unresolved: Set<ObjectEngineResult.GroundKey>,
): Set<ObjectEngineResult.GroundKey> {
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
context(world: Assumptions, resolverSupport: ResolverSupport)
internal fun EngineObjectData.Sync.resolveKey(
    path: List<PathComponent>,
    fieldSelection: ObjectSelection,
    resolved: ObjectEngineResult,
): ResolvePassiveValuesResult? {
    val key = fieldSelection.groundKey()
    val cell = resolved.reserveCell(key)
    return when (val arguments = key.arguments) {
        Arguments.Error -> {
            val errorResult = ErrorEngineResult.of(EngineErrorData.of())
            cell.setValue(errorResult)
            cell.setAccessResult(errorResult)
            null
        }

        is Arguments.Resolved -> {
            val resolutionSelections = resolverSupport.complete(fieldSelection.subselections)
            val fieldValue =
                if (key.field in world.resolverRegistry) {
                    val resolver = world.resolverRegistry.resolver(key.field)
                    val coordinate = path + key
                    val objectFragment = resolver.instantiateObjectFragmentAt(coordinate)
                    val input =
                        runBlocking {
                            resolved.materialize(
                                selections = objectFragment.materializeSelections,
                                reader = coordinate,
                            )
                        }
                    if (world.selectiveResolvers) {
                        resolver(
                            input = input,
                            arguments = arguments,
                            selections = resolutionSelections,
                        )
                    } else {
                        resolver(
                            input = input,
                            arguments = arguments,
                        )
                    }
                } else {
                    outputValue(key.field.name)
                }
            val passiveValuesResult =
                fieldValue.resolvePassiveValues(
                    expectedType = key.field.outputType,
                    path = path + key,
                    resolverDemand = resolutionSelections,
                )
            cell.setValue(passiveValuesResult.engineResult)
            cell.setAccessResult(true)
            passiveValuesResult
        }
    }
}
