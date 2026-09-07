package semantics.resolver26

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import model.ObjectEngineResult
import model.requireQueryTypeDef
import semantics.correctresolution.argumentsContainErrorValue

/**
 * Reads object-path providers and installs every local field resolver.
 *
 * Each installation claims the original symbolic target cell and registers its writer for cycle
 * detection before launching the field-resolver task. This function waits for all installations
 * before returning so the enclosing orchestration can freeze the target, while argument grounding
 * and the rest of the launched field-resolver tasks may continue afterward. Freezing the target
 * without waiting for installation would race with those installations reserving their cells.
 */
internal suspend fun ObjectOrchestrationTask.launchBindingsAndResolvers(
    closed: CloseInputDemandResult,
) {
    context(operation) {
        closed.fieldResolverOccurrenceContexts.values.forEach { fieldResolverOccurrenceContext ->
            operation.queryValuesState.declare(
                fieldResolverOccurrenceContext.resolverOccurrenceId,
            )
        }
        coroutineScope {
            launch {
                occurrence.target.completeProviderBindings(
                    reads = closed.objectProviderReads,
                )
            }
            closed.fieldResolverOccurrenceContexts.forEach {
                    (objectKey, fieldResolverOccurrenceContext) ->
                launch {
                    val queryValue =
                        if (
                            objectKey is ObjectEngineResult.GroundKey &&
                            objectKey.arguments.argumentsContainErrorValue()
                        ) {
                            model.engineObjectDataOf(operation.schema.requireQueryTypeDef())
                        } else {
                            fieldResolverOccurrenceContext.fragments.queryFragment
                                .resolveQueryFragment(
                                    coordinate = occurrence.coordinate(objectKey),
                                    inclusionCondition =
                                        fieldResolverOccurrenceContext.selection.inclusionCondition,
                                )
                        }
                    operation.queryValuesState.complete(
                        fieldResolverOccurrenceContext.resolverOccurrenceId,
                        queryValue,
                    )
                }
                launch {
                    installAndLaunchFieldResolver(
                        fieldResolverOccurrenceContext = fieldResolverOccurrenceContext,
                    )
                }
            }
        }
    }
}

// Installs one active selection while retaining its symbolic cell key.
private fun ObjectOrchestrationTask.installAndLaunchFieldResolver(
    fieldResolverOccurrenceContext: FieldResolverOccurrenceContext,
) {
    context(operation) {
        val objectKey = fieldResolverOccurrenceContext.selection.key
        check(objectKey.field in operation.resolverRegistry) {
            "Resolver26 attempted to install passive key $objectKey"
        }
        check(!source.isPresent(objectKey.field.name)) {
            "Resolver26 attempted to install source-provided key $objectKey"
        }
        val cell = occurrence.target.reserveCell(objectKey)
        cell.createValuePromise()
        operation.cycleChecker.registerWriter(
            cell = cell,
            writer = occurrence.coordinate(objectKey),
        )
        val fieldResolverTask =
            FieldResolverTask(
                operationContext = operation,
                oerOccurrenceContext = occurrence,
                fieldResolverOccurrenceContext = fieldResolverOccurrenceContext,
                cell = cell,
            )
        operation.requestScope.launch {
            fieldResolverTask.run()
        }
    }
}
