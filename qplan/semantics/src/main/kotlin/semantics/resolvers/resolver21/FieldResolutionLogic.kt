package semantics.resolvers.resolver21

import model.Arguments
import model.EngineErrorData
import model.EngineObjectOrErrorData
import model.EngineResultCell
import model.ErrorEngineResult
import model.NodeReferenceIdentity
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.groundKey
import model.invariants.conformsToResolverOutputSchemaType
import model.nodeReferenceIdentityOrNull
import semantics.resolvers.emptyObjectInput
import semantics.resolvers.prepareInvocation
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.materialize
import semantics.shared.withAuthoritativeNodeId

/** Invokes and publishes one already-installed field resolver or root-field reference. */
internal class FieldResolutionLogic(
    private val fieldResolverTask: CoroutineFieldResolverTask,
    private val publicationCell: EngineResultCell,
) {
    private val operationContext get() = fieldResolverTask.operationContext
    private val oerOccurrenceContext get() = fieldResolverTask.oerOccurrenceContext
    private val resolverContext get() = fieldResolverTask.context

    /** Publishes into the cell already activated by task preparation. */
    fun publishFieldError(cause: Exception) {
        publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of(cause)))
    }

    suspend fun publishResult(): Unit = context(operationContext, operationContext.world) {
        val key = resolverContext.selection.groundKey()
        val constructionDemand = resolverContext.publicationConstructionDemand
        val invocationDemand = resolverContext.invocationDemand ?: operationContext.complete(constructionDemand)
        var fieldValue: ResolverOutputData? = resolverContext.reference ?: when (val arguments = key.arguments) {
            Arguments.Error -> {
                check(publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of()))) {
                    "Cell value was completed twice"
                }
                return@context
            }
            is Arguments.Resolved -> runFieldResolver(arguments, invocationDemand)
        }
        var authoritativeNodeIdentity: NodeReferenceIdentity? = null
        while (fieldValue is RootFieldReferenceData) {
            val reference = fieldValue
            authoritativeNodeIdentity = authoritativeNodeIdentity ?: reference.nodeReferenceIdentityOrNull()
            require(reference.conformsToResolverOutputSchemaType(resolverContext.publicationExpectedType)) {
                "Root-field reference does not conform to ${resolverContext.publicationExpectedType}"
            }
            fieldValue = invokeRootFieldResolver(reference, invocationDemand)
        }
        fieldValue = fieldValue.withAuthoritativeNodeId(authoritativeNodeIdentity, invocationDemand)
        val passiveValue = operationContext.passiveValues.resolvePassiveValues(
            value = fieldValue,
            root = oerOccurrenceContext.root,
            expectedType = resolverContext.publicationExpectedType,
            path = resolverContext.publicationPath,
            constructionDemand = constructionDemand,
            invocationDemand = invocationDemand,
            parent = oerOccurrenceContext,
        )
        check(publicationCell.getValue().complete(passiveValue)) { "Cell value was completed twice" }
    }

    private suspend fun runFieldResolver(
        arguments: Arguments.Resolved,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? = context(operationContext, operationContext.world, operationContext.cycleChecker) {
        val resolver = operationContext.resolverRegistry.resolver(resolverContext.selection.key.field)
        val fragments = resolver.instantiateFragmentsAt(oerOccurrenceContext.root, resolverContext.publicationPath)
        val queryProducer = fieldResolverTask.launchQueryFragmentProducer(fragments.queryFragment, resolverContext.publicationPath)
        val input = oerOccurrenceContext.target.materialize(
            selections = fragments.objectFragment.materializeSelections,
            reader = resolverContext.publicationPath,
        )
        val queryValue = when (val value = queryProducer.await()) {
            is EngineObjectOrErrorData.Success -> value.value
            is EngineObjectOrErrorData.Error -> return@context value.error
        }
        resolver(input, queryValue, arguments, invocationDemand)
    }

    private suspend fun invokeRootFieldResolver(
        reference: RootFieldReferenceData,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? = context(operationContext, operationContext.world) {
        val invocation = reference.prepareInvocation()
        val queryProducer = fieldResolverTask.launchQueryFragmentProducer(invocation.fragments.queryFragment, invocation.path)
        val queryValue = when (val value = queryProducer.await()) {
            is EngineObjectOrErrorData.Success -> value.value
            is EngineObjectOrErrorData.Error -> return@context value.error
        }
        val output = invocation.resolver(
            input = invocation.emptyObjectInput(),
            queryValue = queryValue,
            arguments = reference.arguments,
            selections = invocationDemand,
        )
        operationContext.resolverObserver.onRootFieldReferenceInvocation(
            RootFieldReferenceInvocationObservation(
                publicationRoot = oerOccurrenceContext.root,
                publicationPath = resolverContext.publicationPath,
                reference = reference,
                invocationRoot = invocation.root,
                invocationPath = invocation.path,
                invocationKey = invocation.key,
                suppliedDemand = invocationDemand,
            ),
        )
        output
    }
}
