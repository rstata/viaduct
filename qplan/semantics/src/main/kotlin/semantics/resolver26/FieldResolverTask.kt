package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import model.EngineErrorData
import model.EngineObjectOrErrorData
import model.EngineResultCell
import model.InclusionCondition
import model.ObjectEngineResult
import model.PathComponent
import model.VariableBinding
import model.engineObjectDataOf
import model.guardedBy
import model.outputValue
import model.registry.ResolverFragment
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import model.schemaType
import semantics.correctresolution.argumentsContainErrorValue
import viaduct.engine.api.EngineObjectData

internal interface FieldResolverTaskContext {
    val operationContext: Resolver26OperationContext
    val oerOccurrenceContext: OEROccurrenceContext
    val resolverOccurrenceContext: ResolverOccurrenceContext
    val fieldResolverOccurrenceContext: FieldResolverOccurrenceContext?
        get() = resolverOccurrenceContext as? FieldResolverOccurrenceContext

    /** Child scope owned by the field task's request-root job; it cannot launch request roots. */
    val fieldTaskScope: CoroutineScope
}

/** Owns setup and resolution for one field publication. */
internal class FieldResolverTask private constructor(
    override val operationContext: Resolver26OperationContext,
    override val oerOccurrenceContext: OEROccurrenceContext,
    override val resolverOccurrenceContext: ResolverOccurrenceContext,
    private val publicationCell: EngineResultCell,
    override val fieldTaskScope: CoroutineScope,
    private val objectProviderReads: List<ProviderDefinitionRead>,
) : FieldResolverTaskContext {
    companion object {
        /** Installs and launches every local field task owned by one object orchestration. */
        fun launchAll(
            orchestrationTask: ObjectOrchestrationTask,
            closed: CloseInputDemandResult,
        ) {
            val operationContext = orchestrationTask.operation
            // Declare Query values before dispatch so cancellation-before-entry can terminate them.
            closed.fieldResolverOccurrenceContexts.values.forEach { fieldResolverContext ->
                operationContext.queryValuesState.declare(
                    fieldResolverContext.resolverOccurrenceId,
                )
            }
            closed.fieldResolverOccurrenceContexts.forEach { (objectKey, fieldResolverContext) ->
                check(objectKey.field in operationContext.resolverRegistry) {
                    "Resolver26 attempted to install passive key $objectKey"
                }
                check(!orchestrationTask.source.isPresent(objectKey.field.name)) {
                    "Resolver26 attempted to install source-provided key $objectKey"
                }
                installAndLaunch(
                    operationContext = operationContext,
                    oerOccurrenceContext = orchestrationTask.occurrence,
                    resolverOccurrenceContext = fieldResolverContext,
                    objectProviderReads =
                        closed.objectProviderReadsByResolverOccurrence.getValue(
                            fieldResolverContext.resolverOccurrenceId,
                        ),
                )
            }
            closed.rootFieldReferenceOccurrences.values.forEach { referenceOccurrence ->
                val objectKey = referenceOccurrence.selection.key
                check(
                    orchestrationTask.source.outputValue(objectKey.field.name) ===
                        referenceOccurrence.reference,
                ) {
                    "Resolver26 root reference does not match its source value"
                }
                installAndLaunch(
                    operationContext = operationContext,
                    oerOccurrenceContext = orchestrationTask.occurrence,
                    resolverOccurrenceContext = referenceOccurrence,
                    objectProviderReads = emptyList(),
                )
            }
        }

        // The caller claims and registers the list-element cell before launching its task.
        fun launchForListElement(
            operationContext: Resolver26OperationContext,
            oerOccurrenceContext: OEROccurrenceContext,
            resolverOccurrenceContext: ResolverOccurrenceContext,
            publicationCell: EngineResultCell,
        ) {
            launchTask(
                operationContext = operationContext,
                oerOccurrenceContext = oerOccurrenceContext,
                resolverOccurrenceContext = resolverOccurrenceContext,
                publicationCell = publicationCell,
                objectProviderReads = emptyList(),
            )
        }

        // Installs one field task while retaining its symbolic cell key.
        fun installAndLaunch(
            operationContext: Resolver26OperationContext,
            oerOccurrenceContext: OEROccurrenceContext,
            resolverOccurrenceContext: ResolverOccurrenceContext,
            objectProviderReads: List<ProviderDefinitionRead> = emptyList(),
        ) {
            val objectKey = resolverOccurrenceContext.selection.key
            val publicationCell = oerOccurrenceContext.target.reserveCell(objectKey)
            publicationCell.createValuePromise()
            operationContext.cycleChecker.registerWriter(
                cell = publicationCell,
                writer = oerOccurrenceContext.coordinate(objectKey),
            )
            launchTask(
                operationContext = operationContext,
                oerOccurrenceContext = oerOccurrenceContext,
                resolverOccurrenceContext = resolverOccurrenceContext,
                publicationCell = publicationCell,
                objectProviderReads = objectProviderReads,
            )
        }

        private fun launchTask(
            operationContext: Resolver26OperationContext,
            oerOccurrenceContext: OEROccurrenceContext,
            resolverOccurrenceContext: ResolverOccurrenceContext,
            publicationCell: EngineResultCell,
            objectProviderReads: List<ProviderDefinitionRead>,
        ) {
            operationContext.rootTaskLauncher
                .launchFieldResolverTask {
                    FieldResolverTask(
                        operationContext = operationContext,
                        oerOccurrenceContext = oerOccurrenceContext,
                        resolverOccurrenceContext = resolverOccurrenceContext,
                        publicationCell = publicationCell,
                        fieldTaskScope = this,
                        objectProviderReads = objectProviderReads,
                    ).run()
                }.invokeOnCompletion { cause ->
                    if (cause !is CancellationException) return@invokeOnCompletion

                    publicationCell.cancelValue(cause)
                    val fieldResolverContext =
                        resolverOccurrenceContext as? FieldResolverOccurrenceContext
                            ?: return@invokeOnCompletion
                    operationContext.queryValuesState.cancel(
                        fieldResolverContext.resolverOccurrenceId,
                        cause,
                    )
                    fieldResolverContext.variableDefinitions.forEach { definition ->
                        if (
                            definition.definition == VariableDefinition.FromProvider ||
                            definition.definition is VariableDefinition.FromArgument
                        ) {
                            operationContext.variableBindingsState.cancelBinding(
                                requireNotNull(definition.variable.instanceId),
                                cause,
                            )
                        }
                    }
                    objectProviderReads.forEach { read ->
                        operationContext.variableBindingsState.cancelBinding(
                            requireNotNull(read.definition.variable.instanceId),
                            cause,
                        )
                    }
                    cancelQueryPathBindings(operationContext, fieldResolverContext, cause)
                }
        }

        private fun cancelQueryPathBindings(
            operationContext: Resolver26OperationContext,
            fieldResolverContext: FieldResolverOccurrenceContext,
            cause: CancellationException,
        ) {
            fieldResolverContext.fragments.queryFragment.pathVariableDefinitions.forEach {
                definition ->
                operationContext.variableBindingsState.cancelBinding(
                    requireNotNull(definition.variable.instanceId),
                    cause,
                )
            }
        }
    }

    suspend fun run() {
        val resolutionLogic = FieldResolutionLogic(this, publicationCell)
        try {
            launchTaskSetupCoroutines()
            resolutionLogic.validate()
            resolutionLogic.publishResult()
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            resolutionLogic.publishFieldError(cause)
        }
    }

    private fun launchTaskSetupCoroutines() {
        val fieldResolverContext = fieldResolverOccurrenceContext ?: return
        if (objectProviderReads.isNotEmpty()) {
            context(operationContext) {
                fieldTaskScope.launchProviderBindings(
                    oerOccurrenceContext.target,
                    objectProviderReads,
                )
            }
        }
        launchQueryFragmentProducer()
    }

    fun launchQueryFragmentProducer() {
        val fieldResolverContext = fieldResolverOccurrenceContext ?: return
        launchQueryFragmentProducer(fieldResolverContext)
    }

    fun launchQueryFragmentProducer(
        fieldResolverContext: FieldResolverOccurrenceContext,
    ) {
        val resolverOccurrenceId = fieldResolverContext.resolverOccurrenceId
        val selectionKey = fieldResolverContext.selection.key
        fieldTaskScope
            .launch {
                val queryValue =
                    try {
                        val objectValue =
                            if (
                                selectionKey is ObjectEngineResult.GroundKey &&
                                selectionKey.arguments.argumentsContainErrorValue()
                            ) {
                                engineObjectDataOf(operationContext.schema.requireQueryTypeDef())
                            } else {
                                fieldResolverContext.fragments.queryFragment.resolveQueryFragment(
                                    operationContext = operationContext,
                                    coordinate = fieldResolverContext.invocationPath,
                                    inclusionCondition =
                                        fieldResolverContext.selection.inclusionCondition,
                                )
                            }
                        EngineObjectOrErrorData.of(objectValue)
                    } catch (cause: Exception) {
                        currentCoroutineContext().ensureActive()
                        completeQueryPathBindingsWithError(fieldResolverContext)
                        EngineObjectOrErrorData.of(EngineErrorData.of(cause))
                    }
                check(operationContext.queryValuesState.complete(resolverOccurrenceId, queryValue)) {
                    "Resolver26 Query value was already completed for $resolverOccurrenceId"
                }
            }.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    operationContext.queryValuesState.cancel(resolverOccurrenceId, cause)
                    cancelQueryPathBindings(operationContext, fieldResolverContext, cause)
                }
            }
    }

    private fun completeQueryPathBindingsWithError(
        fieldResolverContext: FieldResolverOccurrenceContext,
    ) {
        fieldResolverContext.fragments.queryFragment.pathVariableDefinitions.forEach { definition ->
            operationContext.variableBindingsState.completeBinding(
                requireNotNull(definition.variable.instanceId),
                VariableBinding.Error,
            )
        }
    }
}

private suspend fun ResolverFragment.resolveQueryFragment(
    operationContext: Resolver26OperationContext,
    coordinate: List<PathComponent>,
    inclusionCondition: InclusionCondition,
): EngineObjectData.Sync {
    if (constructionSelections.isEmpty()) {
        return engineObjectDataOf(operationContext.schema.requireQueryTypeDef())
    }

    val symbolicSelections = materializeSelections.guardedBy(inclusionCondition)
    val providerDemand =
        constructionSelections.providerDemand(
            definitions = pathVariableDefinitions,
            inclusionCondition = inclusionCondition,
        )
    val source = operationContext.resolverRegistry.createRootQueryInput()
    val queryResult =
        ObjectEngineResult.of(
            type = source.schemaType,
            mutable = true,
        )
    val orchestration =
        ObjectOrchestrationTask(
            operation = operationContext,
            occurrence =
                OEROccurrenceContext(
                    root = queryResult,
                    path = emptyList(),
                    target = queryResult,
                ),
            source = source,
            initialDemand = symbolicSelections.constructionSelections() + providerDemand,
        )
    orchestration.prepare()
    operationContext.resolverObserver.onQueryFragmentResult(resolverOccurrenceId, queryResult)
    orchestration.launch()
    context(operationContext) {
        queryResult.completeProviderBindings(
            reads =
                pathVariableDefinitions.map { definition ->
                    ProviderDefinitionRead(
                        definition = definition,
                        readerPath = coordinate,
                        inclusionCondition = inclusionCondition,
                    )
                },
        )
    }
    return context(operationContext, operationContext.cycleChecker) {
        queryResult.materializeResolverInput(
            selections = symbolicSelections,
            reader = coordinate,
            resultPath = emptyList(),
        )
    }
}
