package semantics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import model.Assumptions
import model.EngineErrorData
import model.EngineResult
import model.EngineResultCell
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.outputType
import model.ObjectSelection
import model.Arguments
import model.PathComponent
import model.Promise
import model.SelectionForest
import model.groundKey
import model.outputValue
import model.schemaType
import semantics.correctresolution.argumentsContainErrorValue
import viaduct.engine.api.EngineObjectData

/**
 * Resolves [selections] through one structured coroutine tree rooted at this object occurrence.
 *
 * Each demanded value promise is installed before its producer launches. A producer publishes an
 * active child OER only after installing every demanded promise on that child.
 */
context(world: Assumptions, resolverSupport: ResolverSupport)
internal suspend fun EngineObjectData.Sync.coroutineResolve(
    selections: SelectionForest,
): ObjectEngineResult {
    val result =
        ObjectEngineResult.of(
            type = schemaType,
            mutable = true,
        )

    coroutineScope {
        orchestrateSlot(
            path = emptyList(),
            source = this@coroutineResolve,
            selections = selections,
            target = result,
        )
    }

    return result
}

context(world: Assumptions, resolverSupport: ResolverSupport)
private fun CoroutineScope.orchestrateSlot(
    path: List<PathComponent>,
    source: EngineObjectData.Sync,
    selections: SelectionForest,
    target: ObjectEngineResult,
) {
    require(source.schemaType == target.type) {
        "Source type ${source.schemaType.name} does not match result type ${target.type.name}"
    }

    val closedDemand = source.schemaType.closeResolverDemand(path, selections)
    val unresolvedKeys = closedDemand.groundKeys() - target.keys

    unresolvedKeys.forEach { key ->
        val cell = target.reserveCell(key)
        cell.createValuePromise()
        resolverSupport.registerWriter(
            cell = cell,
            writer = path + key,
        )
    }

    unresolvedKeys.forEach { key ->
        launch(start = CoroutineStart.DEFAULT) {
            resolveSlot(
                path = path,
                source = source,
                selection = closedDemand[key],
                target = target,
                cell = target.getCell(key),
            )
        }
    }
}

context(world: Assumptions, resolverSupport: ResolverSupport)
private suspend fun resolveSlot(
    path: List<PathComponent>,
    source: EngineObjectData.Sync,
    selection: ObjectSelection,
    target: ObjectEngineResult,
    cell: EngineResultCell,
) {
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
                val resolutionSelections = resolverSupport.complete(selection.subselections)
                val fieldValue =
                    if (key.field in world.resolverRegistry) {
                        val resolver = world.resolverRegistry.resolver(key.field)
                        val coordinate = path + key
                        val objectFragment = resolver.instantiateObjectFragmentAt(coordinate)
                        val input =
                            target.materialize(
                                selections = objectFragment.materializeSelections,
                                reader = coordinate,
                            )
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
                        source.outputValue(key.field.name)
                    }
                val passiveValuesResult =
                    fieldValue.resolvePassiveValues(
                        expectedType = key.field.outputType,
                        path = path + key,
                        resolverDemand = resolutionSelections,
                    )

                passiveValuesResult.objectsNeedingResolution.forEach { child ->
                    orchestrateSlot(
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
