package semantics.resolvers.resolver01

import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineErrorData
import model.ErrorEngineResult
import model.NodeReferenceIdentity
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.engineObjectDataOf
import model.groundKey
import model.invariants.conformsToResolverOutputSchemaType
import model.nodeReferenceIdentityOrNull
import model.registry.ResolverFragment
import model.registry.ResolutionExecutionContext
import model.requireQueryTypeDef
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.resolvers.emptyObjectInput
import semantics.resolvers.prepareInvocation
import semantics.shared.CycleCheckState
import semantics.shared.SharedFieldResolverTask
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.resolvers.materializeResolverInput
import semantics.shared.withAuthoritativeNodeId
import viaduct.engine.api.EngineObjectData

/**
 * Invokes and publishes one field for Resolver01-03 and Resolver06-08, retaining the grounded
 * publication context supplied to its dispatcher.
 */
internal class DepthFirstFieldResolverTask private constructor(
    override val publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
) : SharedFieldResolverTask<GroundedFieldPublicationOccurrence<DepthFirstOperationContext>>, DepthFirstTask {
    // List-element references sit deeper than the field whose output contains them.
    override val path get() = publication.publicationPath.dropLast(1)

    companion object {
        /** Claims the publication synchronously before either execution or reactor enqueue. */
        fun create(publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>): DepthFirstFieldResolverTask {
            require(publication.selection.key.field.containingDef == publication.oerOccurrence.target.type) {
                "Resolver selection does not belong to its target object"
            }
            // The reactor may freeze the OER before this task runs.
            publication.publicationCell.createValuePromise()
            publication.publicationCell.setActivated(true)
            return DepthFirstFieldResolverTask(publication)
        }
    }

    /** Invokes one field, follows reference tails, and publishes its passively resolved output. */
    fun run(): Unit = with(publication) {
        val key = selection.groundKey()
        val invocationDemand = this.invocationDemand ?: operation.complete(selection.subselections)
        var value: ResolverOutputData? = reference ?: when (val arguments = key.arguments) {
            Arguments.Error -> {
                check(publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of()))) {
                    "Cell value was completed twice"
                }
                return@with
            }
            is Arguments.Resolved -> {
                val resolver = operation.world.resolverRegistry.resolver(key.field)
                val fragments = resolver.instantiateFragmentsAt(oerOccurrence.root, publicationPath)
                val input = runBlocking {
                    // Sibling dependency order and depth-first dispatch make this input ready.
                    oerOccurrence.target.materializeResolverInput(
                        operation = operation,
                        cycleChecker = CycleCheckState.createNOP(),
                        selections = fragments.objectFragment.materializeSelections,
                        reader = publicationPath,
                    )
                }
                runBlocking {
                    context(operation.world) {
                        resolver(
                            input = input,
                            queryValue = resolveQueryFragment(fragments.queryFragment, publicationPath),
                            arguments = arguments,
                            selections = invocationDemand,
                            executionContext = ResolutionExecutionContext.Unsupported,
                        )
                    }
                }
            }
        }
        var nodeIdentity: NodeReferenceIdentity? = null
        while (value is RootFieldReferenceData) {
            val reference = value
            require(reference.conformsToResolverOutputSchemaType(publicationExpectedType)) {
                "Root-field reference does not conform to ${publicationExpectedType}"
            }
            nodeIdentity = nodeIdentity ?: reference.nodeReferenceIdentityOrNull()
            value = resolveRootFieldReference(
                reference, oerOccurrence.root, publicationPath, invocationDemand,
            )
        }
        val result = operation.passiveValues.resolvePassiveValues(
            value = value.withAuthoritativeNodeId(nodeIdentity, invocationDemand),
            root = oerOccurrence.root,
            expectedType = publicationExpectedType,
            path = publicationPath,
            constructionDemand = selection.subselections,
            invocationDemand = invocationDemand,
            parent = oerOccurrence,
        )
        check(publicationCell.getValue().complete(result)) { "Cell value was completed twice" }
    }

    /** Invokes one independently rooted reference target using this resolver's Query-fragment policy. */
    private fun resolveRootFieldReference(
        reference: RootFieldReferenceData,
        publicationRoot: ObjectEngineResult,
        publicationPath: List<PathComponent>,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? {
        val operation = publication.operation
        val invocation = reference.prepareInvocation(operation)
        val queryValue =
            resolveQueryFragment(
                queryFragment = invocation.fragments.queryFragment,
                coordinate = invocation.path,
            )
        val output =
            runBlocking {
                context(operation.world) {
                    invocation.resolver(
                        input = invocation.emptyObjectInput(),
                        queryValue = queryValue,
                        arguments = reference.arguments,
                        selections = invocationDemand,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    )
                }
            }
        operation.resolverObserver.onRootFieldReferenceInvocation(
            RootFieldReferenceInvocationObservation(
                publicationRoot = publicationRoot,
                publicationPath = publicationPath,
                reference = reference,
                invocationRoot = invocation.root,
                invocationPath = invocation.path,
                invocationKey = invocation.key,
                suppliedDemand = invocationDemand,
            ),
        )
        return output
    }

    /**
     * Resolves a fresh Query OER recursively, with its own dispatcher, for both depth-first variants.
     * Its work cannot consume the enclosing passive traversal's accumulated fringe or reactor queue.
     */
    private fun resolveQueryFragment(
        queryFragment: ResolverFragment,
        coordinate: List<PathComponent>,
    ): EngineObjectData.Sync {
        val operation = publication.operation
        if (queryFragment.constructionSelections.isEmpty()) {
            return engineObjectDataOf(operation.world.schema.requireQueryTypeDef())
        }
        val queryResult = DepthFirstResolve(operation, operation.complete)
            .resolve(queryFragment.constructionSelections)
        operation.resolverObserver.onQueryFragmentResult(queryFragment.resolverOccurrenceId, queryResult)
        return runBlocking {
            queryResult.materializeResolverInput(
                operation = operation,
                cycleChecker = CycleCheckState.createNOP(),
                selections = queryFragment.materializeSelections,
                reader = coordinate,
            )
        }
    }
}
