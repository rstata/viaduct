package semantics.resolvers.resolver21

import model.Arguments
import model.EngineErrorData
import model.EngineObjectOrErrorData
import model.ErrorEngineResult
import model.NodeReferenceIdentity
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.groundKey
import model.invariants.conformsToResolverOutputSchemaType
import model.nodeReferenceIdentityOrNull
import model.registry.ResolutionExecutionContext
import semantics.resolvers.emptyObjectInput
import semantics.resolvers.prepareInvocation
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.materialize
import semantics.shared.withAuthoritativeNodeId

/** Invokes and publishes one already-installed field resolver or root-field reference. */
internal class FieldResolutionLogic(
    private val fieldResolverTask: CoroutineFieldResolverTask,
) {
    /** Publishes into the cell already activated by task preparation. */
    fun publishFieldError(cause: Exception) {
        val publication = fieldResolverTask.publication
        publication.publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of(cause)))
    }

    suspend fun publishResult() {
        val publication = fieldResolverTask.publication
        context(publication.operation) {
            val key = publication.selection.groundKey()
            val constructionDemand = publication.selection.subselections
            val invocationDemand = publication.invocationDemand ?: publication.operation.complete(constructionDemand)
            var fieldValue: ResolverOutputData? = publication.reference ?: when (val arguments = key.arguments) {
                Arguments.Error -> {
                    check(publication.publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of()))) {
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
                require(reference.conformsToResolverOutputSchemaType(publication.publicationExpectedType)) {
                    "Root-field reference does not conform to ${publication.publicationExpectedType}"
                }
                fieldValue = invokeRootFieldResolver(reference, invocationDemand)
            }
            fieldValue = fieldValue.withAuthoritativeNodeId(authoritativeNodeIdentity, invocationDemand)
            val passiveValue = publication.operation.passiveValues.resolvePassiveValues(
                value = fieldValue,
                root = publication.oerOccurrence.root,
                expectedType = publication.publicationExpectedType,
                path = publication.publicationPath,
                constructionDemand = constructionDemand,
                invocationDemand = invocationDemand,
                parent = publication.oerOccurrence,
            )
            check(publication.publicationCell.getValue().complete(passiveValue)) { "Cell value was completed twice" }
        }
    }

    private suspend fun runFieldResolver(
        arguments: Arguments.Resolved,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val publication = fieldResolverTask.publication
        return context(publication.operation, publication.operation.cycleChecker) {
            val resolver = publication.operation.world.resolverRegistry.resolver(publication.selection.key.field)
            val fragments = resolver.instantiateFragmentsAt(publication.oerOccurrence.root, publication.publicationPath)
            val queryProducer = fieldResolverTask.launchQueryFragmentProducer(fragments.queryFragment, publication.publicationPath)
            val input = publication.oerOccurrence.target.materialize(
                selections = fragments.objectFragment.materializeSelections,
                reader = publication.publicationPath,
            )
            val queryValue = when (val value = queryProducer.await()) {
                is EngineObjectOrErrorData.Success -> value.value
                is EngineObjectOrErrorData.Error -> return@context value.error
            }
            context(publication.operation.world) {
                resolver(
                    input,
                    queryValue,
                    arguments,
                    invocationDemand,
                    ResolutionExecutionContext.Unsupported,
                )
            }
        }
    }

    private suspend fun invokeRootFieldResolver(
        reference: RootFieldReferenceData,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val publication = fieldResolverTask.publication
        return context(publication.operation) {
            val invocation = reference.prepareInvocation()
            val queryProducer = fieldResolverTask.launchQueryFragmentProducer(invocation.fragments.queryFragment, invocation.path)
            val queryValue = when (val value = queryProducer.await()) {
                is EngineObjectOrErrorData.Success -> value.value
                is EngineObjectOrErrorData.Error -> return@context value.error
            }
            val output = context(publication.operation.world) {
                invocation.resolver(
                    input = invocation.emptyObjectInput(),
                    queryValue = queryValue,
                    arguments = reference.arguments,
                    selections = invocationDemand,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            }
            publication.operation.resolverObserver.onRootFieldReferenceInvocation(
                RootFieldReferenceInvocationObservation(
                    publicationRoot = publication.oerOccurrence.root,
                    publicationPath = publication.publicationPath,
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
}
