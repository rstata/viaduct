package semantics.resolvers.resolver01

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineErrorData
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
import model.registry.ResolverFragment
import model.schemaType
import semantics.correctresolution.argumentsContainErrorValue
import semantics.resolvers.ResolvePassiveValuesResult
import semantics.resolvers.closeResolverDemand
import semantics.resolvers.materializedChildOccurrences
import semantics.resolvers.resolvePassiveValues
import semantics.resolvers.resolveRetainedObjects
import semantics.shared.CycleCheckState
import semantics.shared.OperationContext
import semantics.shared.materialize

/** The recursive, dependency-first resolution constructor shared by Resolver01-03 and 06-08. */
internal class DepthFirstResolve(
    private val operation: OperationContext,
    private val complete: (SelectionForest) -> SelectionForest,
) {
    private val world: Assumptions = operation.world

    fun resolve(
        source: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult {
        val result =
            context(operation, world) {
                ObjectEngineResult.of(source.schemaType, emptyMap(), mutable = true)
            }
        return orchestrateKeys(
            source = source,
            root = result,
            path = emptyList(),
            selections = selections,
            resolved = result,
        )
    }

    /** Resolves [selections] at this exact object occurrence, extending [resolved]. */
    fun orchestrateKeys(
        source: EngineObjectData.Sync,
        root: ObjectEngineResult,
        path: List<PathComponent>,
        selections: SelectionForest,
        resolved: ObjectEngineResult,
    ): ObjectEngineResult = context(operation, world) {
        require(resolved.type == source.schemaType) {
            "Initial result type ${resolved.type.name} does not match ${source.schemaType}"
        }

        val closedDemand = source.closeResolverDemand(root, path, selections)
        source.materializedChildOccurrences(path, closedDemand, resolved)
            .forEach { passiveObjectOccurrence ->
                orchestrateKeys(
                    source = passiveObjectOccurrence.source,
                    root = root,
                    path = passiveObjectOccurrence.path,
                    selections = passiveObjectOccurrence.selections,
                    resolved = passiveObjectOccurrence.target,
                )
            }
        val unresolvedKeys = closedDemand.groundKeys() - resolved.requireGroundKeys()
        require(unresolvedKeys.none { key -> key is ObjectEngineResult.ParentKey }) {
            "Resolver01-03 do not support @parent fields"
        }
        val orderedKeys = dependencyOrder(source, root, path, unresolvedKeys)
        orderedKeys.forEach { key ->
            val selection = closedDemand[key]
            resolveKey(source, root, path, selection, resolved)
                ?.resolveRetainedObjects { passiveObjectOccurrence ->
                    orchestrateKeys(
                        source = passiveObjectOccurrence.source,
                        root = root,
                        path = passiveObjectOccurrence.path,
                        selections = passiveObjectOccurrence.selections,
                        resolved = passiveObjectOccurrence.target,
                    )
                }
        }
        resolved
    }

    /** Returns a topological ordering of [keys] using Kahn's algorithm, demand first. */
    fun dependencyOrder(
        source: EngineObjectData.Sync,
        root: ObjectEngineResult,
        path: List<PathComponent>,
        keys: Set<ObjectEngineResult.GroundKey>,
        ordered: List<ObjectEngineResult.GroundKey> = emptyList(),
    ): List<ObjectEngineResult.GroundKey> = context(operation, world) {
        if (keys.isEmpty()) return@context ordered

        val ready =
            keys.filter { key ->
                dependenciesOf(source, root, path, key, keys).isEmpty()
            }.toSet()
        require(ready.isNotEmpty()) {
            "Resolver dependencies on ${source.schemaType.name} contain a cycle"
        }
        dependencyOrder(
            source = source,
            root = root,
            path = path,
            keys = keys - ready,
            ordered = ordered + ready,
        )
    }

    /** Returns the unresolved sibling keys demanded by the field resolver for [consumer]. */
    private fun dependenciesOf(
        source: EngineObjectData.Sync,
        root: ObjectEngineResult,
        path: List<PathComponent>,
        consumer: ObjectEngineResult.GroundKey,
        unresolved: Set<ObjectEngineResult.GroundKey>,
    ): Set<ObjectEngineResult.GroundKey> = context(operation, world) {
        if (consumer.arguments.argumentsContainErrorValue()) {
            return@context emptySet()
        }
        require(consumer.field in world.resolverRegistry) {
            "Demanded field ${source.schemaType.name}/${consumer.field.name} is absent from its source " +
                "and has no registered resolver"
        }

        unresolved
            .filter { sibling ->
                sibling != consumer &&
                    consumer.demandsFromSibling(sibling, root, path + consumer)
            }.toSet()
    }

    /** Resolves one field and yields its passive result-tree fringe. */
    fun resolveKey(
        source: EngineObjectData.Sync,
        root: ObjectEngineResult,
        path: List<PathComponent>,
        fieldSelection: ObjectSelection,
        resolved: ObjectEngineResult,
    ): ResolvePassiveValuesResult? = context(operation, world) {
        val key = fieldSelection.groundKey()
        val cell = resolved.reserveCell(key)
        when (val arguments = key.arguments) {
            Arguments.Error -> {
                val errorResult = ErrorEngineResult.of(EngineErrorData.of())
                cell.setValue(errorResult)
                cell.setAccessResult(errorResult)
                null
            }

            is Arguments.Resolved -> {
                require(key.field in world.resolverRegistry) {
                    "Always passive field ${source.schemaType.name}/${key.field.name} can't be actively resolved."
                }
                require(!source.isPresent(key.field.name)) {
                    "Passively-resolved field ${source.schemaType.name}/${key.field.name} can't be actively resolved."
                }
                val invocationDemand = complete(fieldSelection.subselections)
                val resolver = world.resolverRegistry.resolver(key.field)
                val coordinate = path + key
                val fragments = resolver.instantiateFragmentsAt(root, coordinate)
                val objectFragment = fragments.objectFragment
                val input =
                    runBlocking {
                        // Depth-first resolution guarantees this materialization does not block.
                        context(operation, CycleCheckState.createNOP()) {
                            resolved.materialize(
                                selections = objectFragment.materializeSelections,
                                reader = coordinate,
                            )
                        }
                    }
                val queryValue = resolveQueryFragment(fragments.queryFragment, coordinate)
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

    private fun resolveQueryFragment(
        queryFragment: ResolverFragment,
        coordinate: List<PathComponent>,
    ): EngineObjectData.Sync = context(operation, world) {
        if (queryFragment.constructionSelections.isEmpty()) {
            return@context engineObjectDataOf(world.schema.requireQueryTypeDef())
        }

        val source = world.resolverRegistry.createRootQueryInput()
        val queryResult =
            ObjectEngineResult.of(
                type = source.schemaType,
                values = emptyMap(),
                mutable = true,
            )
        orchestrateKeys(
            source = source,
            root = queryResult,
            path = emptyList(),
            selections = queryFragment.constructionSelections,
            resolved = queryResult,
        )
        operation.resolverObserver.onQueryFragmentResult(
            queryFragment.resolverOccurrenceId,
            queryResult,
        )
        runBlocking {
            context(operation, CycleCheckState.createNOP()) {
                queryResult.materialize(
                    selections = queryFragment.materializeSelections,
                    reader = coordinate,
                )
            }
        }
    }
}

internal fun ObjectEngineResult.requireGroundKeys(): Set<ObjectEngineResult.GroundKey> =
    keys.mapTo(linkedSetOf()) { key ->
        require(key is ObjectEngineResult.GroundKey) {
            "This resolver family requires grounded OER keys"
        }
        key
    }
