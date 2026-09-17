package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
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
import semantics.shared.SharedFieldResolverContext
import semantics.shared.SharedFieldResolverTask
import semantics.shared.OEROccurrenceContext
import viaduct.engine.api.EngineObjectData

/** Prepared field publication handed to the request dispatcher before its coroutine exists. */
internal class FieldResolverContext(
    override val operationContext: OperationContext,
    override val oerOccurrenceContext: OEROccurrenceContext,
    val resolverOccurrenceContext: ResolverOccurrenceContext,
    override val publicationCell: EngineResultCell,
    val objectProviderReads: List<ProviderDefinitionRead>,
) : SharedFieldResolverContext {
    override val selection get() = resolverOccurrenceContext.selection
    override val publicationPath get() = resolverOccurrenceContext.publicationPath
    override val publicationExpectedType get() = resolverOccurrenceContext.publicationExpectedType
    override val publicationConstructionDemand get() = resolverOccurrenceContext.publicationConstructionDemand
}

/** Owns setup and resolution for one field publication. */
internal class FieldResolverTask private constructor(
    override val operationContext: OperationContext,
    override val oerOccurrenceContext: OEROccurrenceContext,
    val resolverOccurrenceContext: ResolverOccurrenceContext,
    private val publicationCell: EngineResultCell,
    /** Child scope owned by the field task's request-root job; it cannot launch request roots. */
    val fieldTaskScope: CoroutineScope,
    private val objectProviderReads: List<ProviderDefinitionRead>,
) : SharedFieldResolverTask {
    val fieldResolverOccurrenceContext: FieldResolverOccurrenceContext?
        get() = resolverOccurrenceContext as? FieldResolverOccurrenceContext

    companion object {
        /** Installs and launches every local field task owned by one object orchestration. */
        fun launchAll(
            orchestrationTask: OrchestrationTask,
            closed: CloseInputDemandResult,
        ) {
            val operationContext = orchestrationTask.operation
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

        // Called by ResolvePassiveValues to launch a list-element task.
        // The caller has already claimed the cell and registered its writer.
        fun launchForListElement(
            operationContext: OperationContext,
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
            operationContext: OperationContext,
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
            operationContext: OperationContext,
            oerOccurrenceContext: OEROccurrenceContext,
            resolverOccurrenceContext: ResolverOccurrenceContext,
            publicationCell: EngineResultCell,
            objectProviderReads: List<ProviderDefinitionRead>,
        ) {
            operationContext.dispatcher.dispatchFieldResolver(
                FieldResolverContext(
                    operationContext, oerOccurrenceContext, resolverOccurrenceContext,
                    publicationCell, objectProviderReads,
                ),
            )
        }

        /** Enters the existing field-task body under its dispatched coroutine's scope. */
        internal suspend fun execute(context: FieldResolverContext, scope: CoroutineScope) {
            FieldResolverTask(
                operationContext = context.operationContext,
                oerOccurrenceContext = context.oerOccurrenceContext,
                resolverOccurrenceContext = context.resolverOccurrenceContext,
                publicationCell = context.publicationCell,
                fieldTaskScope = scope,
                objectProviderReads = context.objectProviderReads,
            ).run()
        }

        /** Terminates owned promises even when cancellation prevents the task body from entering. */
        internal fun cancel(context: FieldResolverContext, cause: CancellationException) {
            with(context) {
                publicationCell.cancelValue(cause)
                val fieldResolverContext =
                    resolverOccurrenceContext as? FieldResolverOccurrenceContext
                        ?: return
                objectProviderReads.forEach { read ->
                    operationContext.variableBindingsState.cancelBinding(
                        requireNotNull(read.definition.variable.instanceId),
                        cause,
                    )
                }
                cancelInvocationBindings(operationContext, fieldResolverContext, cause)
            }
        }

        private fun cancelInvocationBindings(
            operationContext: OperationContext,
            fieldResolverContext: FieldResolverOccurrenceContext,
            cause: CancellationException,
        ) {
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
            val queryProducer = launchTaskSetupCoroutines()
            resolutionLogic.validate()
            resolutionLogic.publishResult(queryProducer)
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            resolutionLogic.publishFieldError(cause)
        }
    }

    // Only ordinary fields have an initial Query producer; references launch one per invocation.
    private fun launchTaskSetupCoroutines(): Deferred<EngineObjectOrErrorData>? {
        val fieldResolverContext = fieldResolverOccurrenceContext ?: return null
        if (objectProviderReads.isNotEmpty()) {
            context(operationContext) {
                fieldTaskScope.launch {
                    oerOccurrenceContext.target.completeProviderBindings(objectProviderReads)
                }
            }
        }
        return launchQueryFragmentProducer(fieldResolverContext)
    }

    /** Returns the Query outcome directly, with binding cleanup on production failure or cancellation. */
    fun launchQueryFragmentProducer(
        fieldResolverContext: FieldResolverOccurrenceContext,
    ): Deferred<EngineObjectOrErrorData> {
        // Register each invocation with the field-task root, including later reference hops.
        // cancel(context, cause) also covers the original bindings before field-task entry.
        fieldTaskScope.coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cancelInvocationBindings(operationContext, fieldResolverContext, cause)
            }
        }
        val selectionKey = fieldResolverContext.selection.key
        return fieldTaskScope
            .async {
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
    operationContext: OperationContext,
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
        OrchestrationTask.create(
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
    operationContext.resolverObserver.onQueryFragmentResult(resolverOccurrenceId, queryResult)
    operationContext.dispatcher.dispatchOrchestrator(orchestration)
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
