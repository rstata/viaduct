package semantics.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import model.EngineErrorData
import model.EngineObjectOrErrorData
import model.EngineResultCell
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.RootFieldReferenceData
import model.SelectionForest
import model.engineObjectDataOf
import model.outputType
import model.outputValue
import model.registry.ResolverFragment
import model.requireQueryTypeDef
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedFieldResolverContext
import semantics.shared.SharedFieldResolverTask
import semantics.shared.materialize
import viaduct.graphql.schema.ViaductSchema

/** Prepared publication handed to the request dispatcher before its coroutine exists. */
internal class CoroutineFieldResolverContext(
    override val operationContext: CoroutineOperationContext,
    override val oerOccurrenceContext: OEROccurrenceContext,
    override val selection: ObjectSelection,
    override val publicationCell: EngineResultCell,
    val reference: RootFieldReferenceData? = null,
    val invocationDemand: SelectionForest? = null,
    override val publicationPath: List<PathComponent> = oerOccurrenceContext.coordinate(selection.key),
    override val publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = selection.key.field.outputType,
) : SharedFieldResolverContext {
    override val publicationConstructionDemand get() = selection.subselections
}

/** Owns field-local helper coroutines and delegates invocation and publication to resolution logic. */
internal class CoroutineFieldResolverTask private constructor(
    val context: CoroutineFieldResolverContext,
    val fieldTaskScope: CoroutineScope,
) : SharedFieldResolverTask {
    override val operationContext get() = context.operationContext
    override val oerOccurrenceContext get() = context.oerOccurrenceContext

    companion object {
        /** Installs all local promises before dispatching any producer, including source references. */
        fun launchAll(orchestrationTask: CoroutineOrchestrationTask, closed: ObjectSelectionForest) {
            val operation = orchestrationTask.operation
            val occurrence = orchestrationTask.occurrence
            val contexts = closed.byGroundKey().filterKeys { !occurrence.target.isCellSet(it) }.map { (key, selection) ->
                val reference = if (orchestrationTask.source.isPresent(key.field.name)) {
                    orchestrationTask.source.outputValue(key.field.name) as RootFieldReferenceData
                } else null
                prepare(
                    CoroutineFieldResolverContext(operation, occurrence, selection, occurrence.target.reserveCell(key), reference),
                )
            }
            contexts.forEach(operation.dispatcher::dispatchFieldResolver)
        }

        /** List references use the same publication protocol at their exact list-element path. */
        fun launchForListElement(context: CoroutineFieldResolverContext) {
            context.operationContext.dispatcher.dispatchFieldResolver(prepare(context))
        }

        private fun prepare(context: CoroutineFieldResolverContext): CoroutineFieldResolverContext = context.apply {
            publicationCell.createValuePromise()
            // List cells are activated when the shared traversal allocates their list.
            if (publicationPath.last() is ObjectEngineResult.ObjectKey) {
                check(publicationCell.setActivated(true)) { "Cell activation was decided twice" }
            }
            operationContext.cycleChecker.registerWriter(publicationCell, publicationPath)
        }

        internal suspend fun execute(context: CoroutineFieldResolverContext, scope: CoroutineScope) {
            CoroutineFieldResolverTask(context, scope).run()
        }
    }

    /** Publishes field failures as values while preserving coroutine cancellation and JVM Errors. */
    suspend fun run() {
        val resolutionLogic = FieldResolutionLogic(this, context.publicationCell)
        try {
            resolutionLogic.publishResult()
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            resolutionLogic.publishFieldError(cause)
        }
    }

    /**
     * Produces an independent Query input under the field scope. Its orchestration and field work
     * are dispatched on the request root, as in Resolver26; the producer awaits only its input.
     * Failures are returned to the owning field without cancelling its scope.
     */
    fun launchQueryFragmentProducer(
        queryFragment: ResolverFragment,
        coordinate: List<PathComponent>,
    ): Deferred<EngineObjectOrErrorData> = fieldTaskScope.async {
        try {
            val queryValue = context(operationContext, operationContext.world, operationContext.cycleChecker) {
                if (queryFragment.constructionSelections.isEmpty()) {
                    engineObjectDataOf(operationContext.schema.requireQueryTypeDef())
                } else {
                    val queryResult = startResolve(
                        operationContext.resolverRegistry.createRootQueryInput(), queryFragment.constructionSelections,
                    )
                    operationContext.resolverObserver.onQueryFragmentResult(queryFragment.resolverOccurrenceId, queryResult)
                    queryResult.materialize(queryFragment.materializeSelections, coordinate)
                }
            }
            EngineObjectOrErrorData.of(queryValue)
        } catch (cause: Exception) {
            currentCoroutineContext().ensureActive()
            EngineObjectOrErrorData.of(EngineErrorData.of(cause))
        }
    }
}
