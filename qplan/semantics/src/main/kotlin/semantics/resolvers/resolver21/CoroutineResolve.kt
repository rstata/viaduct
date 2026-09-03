package semantics.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.EngineResultCell
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.SelectionForest
import model.engineObjectDataOf
import model.groundKey
import model.outputType
import model.registry.ResolverFragment
import model.requireQueryTypeDef
import model.schemaType
import semantics.resolvers.closeResolverDemand
import semantics.resolvers.materializedChildOccurrences
import semantics.resolvers.resolvePassiveValues
import semantics.shared.CycleCheckState
import semantics.shared.OperationContext
import semantics.shared.materialize
import viaduct.engine.api.EngineObjectData

/** Resolves one operation through a structured coroutine tree and exact value promises. */
internal class CoroutineResolve(
    private val operation: OperationContext,
    private val complete: (SelectionForest) -> SelectionForest,
    private val cycleChecker: CycleCheckState = CycleCheckState.create(),
) {
    private val world: Assumptions = operation.world

    suspend fun resolve(
        source: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult = context(operation, world) {
        val result =
            ObjectEngineResult.of(
                type = source.schemaType,
                mutable = true,
            )

        coroutineScope {
            orchestrateSlot(
                root = result,
                path = emptyList(),
                source = source,
                selections = selections,
                target = result,
            )
        }

        result
    }

    private fun CoroutineScope.orchestrateSlot(
        root: ObjectEngineResult,
        path: List<PathComponent>,
        source: EngineObjectData.Sync,
        selections: SelectionForest,
        target: ObjectEngineResult,
    ): Unit = context(operation, world) {
        require(source.schemaType == target.type) {
            "Source type ${source.schemaType.name} does not match result type ${target.type.name}"
        }

        val closedDemand = source.closeResolverDemand(root, path, selections)
        source.materializedChildOccurrences(path, closedDemand, target)
            .forEach { child ->
                orchestrateSlot(
                    root = root,
                    path = child.path,
                    source = child.source,
                    selections = child.selections,
                    target = child.target,
                )
            }
        val unresolvedKeys = closedDemand.groundKeys() - target.keys

        unresolvedKeys.forEach { key ->
            val cell = target.reserveCell(key)
            cell.createValuePromise()
            cycleChecker.registerWriter(
                cell = cell,
                writer = path + key,
            )
        }

        unresolvedKeys.forEach { key ->
            launch(start = CoroutineStart.DEFAULT) {
                resolveSlot(
                    root = root,
                    path = path,
                    source = source,
                    selection = closedDemand[key],
                    target = target,
                    cell = target.getCell(key),
                )
            }
        }
    }

    private suspend fun resolveSlot(
        root: ObjectEngineResult,
        path: List<PathComponent>,
        source: EngineObjectData.Sync,
        selection: ObjectSelection,
        target: ObjectEngineResult,
        cell: EngineResultCell,
    ): Unit = context(operation, world) {
        val key = selection.groundKey()
        val valuePromise = cell.getValue()
        when (val arguments = key.arguments) {
            Arguments.Error -> {
                val errorResult = ErrorEngineResult.of(EngineErrorData.of())
                valuePromise.complete(errorResult)
                cell.setAccessResult(errorResult)
            }

            is Arguments.Resolved ->
                coroutineScope {
                    require(key.field in world.resolverRegistry) {
                        "Always passive field ${source.schemaType.name}/${key.field.name} can't be actively resolved."
                    }
                    require(!source.isPresent(key.field.name)) {
                        "Passively-resolved field ${source.schemaType.name}/${key.field.name} can't be actively resolved."
                    }
                    val invocationDemand = complete(selection.subselections)
                    val resolver = world.resolverRegistry.resolver(key.field)
                    val coordinate = path + key
                    val fragments = resolver.instantiateFragmentsAt(root, coordinate)
                    val objectFragment = fragments.objectFragment
                    val input =
                        context(operation, cycleChecker) {
                            target.materialize(
                                selections = objectFragment.materializeSelections,
                                reader = coordinate,
                            )
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
                            constructionDemand = selection.subselections,
                            invocationDemand = invocationDemand,
                        )

                    passiveValuesResult.objectsNeedingResolution.forEach { child ->
                        orchestrateSlot(
                            root = root,
                            path = child.path,
                            source = child.source,
                            selections = child.selections,
                            target = child.target,
                        )
                    }
                    valuePromise.complete(passiveValuesResult.engineResult)
                    cell.setAccessResult(true)
                }
        }
    }

    private suspend fun CoroutineScope.resolveQueryFragment(
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
                mutable = true,
            )
        orchestrateSlot(
            root = queryResult,
            path = emptyList(),
            source = source,
            selections = queryFragment.constructionSelections,
            target = queryResult,
        )
        operation.resolverObserver.onQueryFragmentResult(
            queryFragment.resolverOccurrenceId,
            queryResult,
        )
        context(operation, cycleChecker) {
            queryResult.materialize(
                selections = queryFragment.materializeSelections,
                reader = coordinate,
            )
        }
    }
}
