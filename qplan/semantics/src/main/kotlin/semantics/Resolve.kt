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
import model.engineObjectDataOf
import model.groundKey
import model.requireQueryTypeDef
import model.registry.demandsFromSibling
import model.registry.ResolverFragment
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
    root: ObjectEngineResult,
    path: List<PathComponent>,
    selections: SelectionForest,
    resolved: ObjectEngineResult,
): ObjectEngineResult {
    require(resolved.type == schemaType) {
        "Initial result type ${resolved.type.name} does not match $schemaType"
    }

    val closedDemand = closeResolverDemand(root, path, selections)
    materializedChildOccurrences(path, closedDemand, resolved)
        .forEach { passiveObjectOccurrence ->
            passiveObjectOccurrence.source.orchestrateKeys(
                root = root,
                path = passiveObjectOccurrence.path,
                selections = passiveObjectOccurrence.selections,
                resolved = passiveObjectOccurrence.target,
            )
        }
    val unresolvedKeys = closedDemand.groundKeys() - resolved.requireGroundKeys()
    val orderedKeys = dependencyOrder(root, path, unresolvedKeys)
    orderedKeys.forEach { key ->
        val selection = closedDemand[key]
        resolveKey(root, path, selection, resolved)
            ?.resolveRetainedObjects { passiveObjectOccurrence ->
                passiveObjectOccurrence.source.orchestrateKeys(
                    root = root,
                    path = passiveObjectOccurrence.path,
                    selections = passiveObjectOccurrence.selections,
                    resolved = passiveObjectOccurrence.target,
                )
            }
    }
    return resolved
}

internal fun ObjectEngineResult.requireGroundKeys(): Set<ObjectEngineResult.GroundKey> =
    keys.mapTo(linkedSetOf()) { key ->
        require(key is ObjectEngineResult.GroundKey) {
            "This resolver family requires grounded OER keys"
        }
        key
    }

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
internal fun EngineObjectData.Sync.dependencyOrder(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    keys: Set<ObjectEngineResult.GroundKey>,
    ordered: List<ObjectEngineResult.GroundKey> = emptyList(),
): List<ObjectEngineResult.GroundKey> {
    if (keys.isEmpty()) return ordered

    val ready =
        keys.filter { key ->
            dependenciesOf(root, path, key, keys).isEmpty()
        }.toSet()
    require(ready.isNotEmpty()) {
        "Resolver dependencies on ${schemaType.name} contain a cycle"
    }
    return dependencyOrder(
        root = root,
        path = path,
        keys = keys - ready,
        ordered = ordered + ready,
    )
}

/** Returns the unresolved sibling keys demanded by the field resolver for [consumer]. */
context(world: Assumptions)
private fun EngineObjectData.Sync.dependenciesOf(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    consumer: ObjectEngineResult.GroundKey,
    unresolved: Set<ObjectEngineResult.GroundKey>,
): Set<ObjectEngineResult.GroundKey> {
    if (consumer.arguments.argumentsContainErrorValue()) {
        return emptySet()
    }
    require(consumer.field in world.resolverRegistry) {
        "Demanded field ${schemaType.name}/${consumer.field.name} is absent from its source " +
            "and has no registered resolver"
    }

    return unresolved
        .filter { sibling ->
            sibling != consumer &&
                consumer.demandsFromSibling(sibling, root, path + consumer)
        }.toSet()
}

/**
 * Resolves and sets the cell value and access result for [fieldSelection], yielding its passive
 * result-tree fringe.
 */
context(world: Assumptions, resolverSupport: ResolverSupport)
internal fun EngineObjectData.Sync.resolveKey(
    root: ObjectEngineResult,
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
            require(key.field in world.resolverRegistry) {
                "Always passive field ${schemaType.name}/${key.field.name} can't be actively resolved."
            }
            require(!isPresent(key.field.name)) {
                "Passively-resolved field ${schemaType.name}/${key.field.name} can't be actively resolved."
            }
            val invocationDemand = resolverSupport.complete(fieldSelection.subselections)
            val resolver = world.resolverRegistry.resolver(key.field)
            val coordinate = path + key
            val fragments = resolver.instantiateFragmentsAt(root, coordinate)
            val objectFragment = fragments.objectFragment
            val input =
                runBlocking {
                    // Because of the depth-first nature of resolvers01-03 && 06-08
                    // resolved.materialize should not block so runBlocking is ok here
                    resolved.materialize(
                        selections = objectFragment.materializeSelections,
                        reader = coordinate,
                    )
                }
            val queryValue = fragments.queryFragment.resolveQueryFragment(coordinate)
            val fieldValue =
                resolver(
                    input = input,
                    queryValue = queryValue,
                    arguments = arguments,
                    selections = invocationDemand,
                )
            val passiveValuesResult =
                fieldValue.resolvePassiveValues(
                    expectedType = key.field.outputType,
                    path = path + key,
                    constructionDemand = fieldSelection.subselections,
                    invocationDemand = invocationDemand,
                )
            cell.setValue(passiveValuesResult.engineResult)
            cell.setAccessResult(true)
            passiveValuesResult
        }
    }
}

context(world: Assumptions, resolverSupport: ResolverSupport)
private fun ResolverFragment.resolveQueryFragment(
    coordinate: List<PathComponent>,
): EngineObjectData.Sync {
    if (constructionSelections.isEmpty()) {
        return engineObjectDataOf(world.schema.requireQueryTypeDef())
    }

    val source = world.resolverRegistry.createRootQueryInput()
    val queryResult: ObjectEngineResult =
        ObjectEngineResult.of(
            type = source.schemaType,
            values = emptyMap(),
            mutable = true,
        )
    source.orchestrateKeys(
        root = queryResult,
        path = emptyList(),
        selections = constructionSelections,
        resolved = queryResult,
    )
    world.queryValues[resolverOccurrenceId] = queryResult
    return runBlocking {
        queryResult.materialize(
            selections = materializeSelections,
            reader = coordinate,
        )
    }
}
