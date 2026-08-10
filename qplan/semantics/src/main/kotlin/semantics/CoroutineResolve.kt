package semantics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.PathComponent
import model.Promise
import model.SelectionForest
import model.Value
import model.groundKey
import semantics.correctresolution.argumentsContainErrorValue

/**
 * Resolves [selections] through one structured coroutine tree rooted at this object occurrence.
 *
 * Each demanded value promise is installed before its producer launches. A producer publishes an
 * active child OER only after installing every demanded promise on that child.
 */
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal suspend fun Value.Object.coroutineResolve(
    selections: SelectionForest,
): EngineResult.Object {
    val result =
        EngineResult.Object.of(
            type = type,
            values = emptyMap(),
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

context(world: Assumptions, runtimeSupport: RuntimeSupport)
private fun CoroutineScope.orchestrateSlot(
    path: List<PathComponent>,
    source: Value.Object,
    selections: SelectionForest,
    target: EngineResult.Object,
) {
    require(source.type == target.type) {
        "Source type ${source.type.typeName} does not match result type ${target.type.typeName}"
    }

    val closedDemand = source.type.closeResolverDemand(path, selections)
    val unresolvedKeys = closedDemand.groundKeys() - target.keys

    unresolvedKeys.forEach { key ->
        target.createValuePromise(key)
        runtimeSupport.registerWriter(
            target = target,
            key = key,
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
                valuePromise = target.getValue(key),
            )
        }
    }
}

context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun resolveSlot(
    path: List<PathComponent>,
    source: Value.Object,
    selection: ObjectSelection,
    target: EngineResult.Object,
    valuePromise: Promise<EngineResult?>,
) {
    val key = selection.groundKey()
    when {
        key.arguments.argumentsContainErrorValue() -> valuePromise.complete(Value.Error)
        key.field.fieldName == "__typename" ->
            valuePromise.complete(Value.String.of(source.type.typeName))
        else ->
            coroutineScope {
                val completion = runtimeSupport.complete(selection.subselections)
                val resolutionSelections = completion.selections
                val fieldValue =
                    if (key.field in world.resolverRegistry) {
                        val resolver = world.resolverRegistry.resolver(key.field)
                        val coordinate = path + key
                        val input =
                            target.materialize(
                                selections = resolver.objectFragmentAt(coordinate),
                                reader = coordinate,
                            )
                        when {
                            completion.retainCompleteOutput ->
                                resolver.completeOutput(
                                    input = input,
                                    arguments = key.arguments,
                                    selections = resolutionSelections,
                                )
                            world.selectiveResolvers ->
                                resolver(
                                    input = input,
                                    arguments = key.arguments,
                                    selections = resolutionSelections,
                                )
                            else ->
                                resolver(
                                    input = input,
                                    arguments = key.arguments,
                                )
                        }
                    } else {
                        require(!world.selectiveResolvers) {
                            "Passive key found ($key)."
                        }
                        source.fieldValues.getValue(key)
                    }
                val resolvedValue =
                    fieldValue.resolveValue(
                        path = path + key,
                        resolverDemand = resolutionSelections,
                        retainCompleteOutput = completion.retainCompleteOutput,
                    )

                resolvedValue.objectsNeedingResolution.forEach { child ->
                    orchestrateSlot(
                        path = child.path,
                        source = child.source,
                        selections = child.selections,
                        target = child.target,
                    )
                }
                valuePromise.complete(resolvedValue.engineResult)
            }
    }
}
