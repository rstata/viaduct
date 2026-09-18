package semantics.resolvers.resolver01

import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineErrorData
import model.EngineResultCell
import model.ErrorEngineResult
import model.NodeReferenceIdentity
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.engineObjectDataOf
import model.groundKey
import model.invariants.conformsToResolverOutputSchemaType
import model.nodeReferenceIdentityOrNull
import model.outputType
import model.registry.ResolverFragment
import model.registry.ResolutionExecutionContext
import model.requireQueryTypeDef
import semantics.resolvers.emptyObjectInput
import semantics.resolvers.prepareInvocation
import semantics.shared.CycleCheckState
import semantics.shared.SharedFieldResolverContext
import semantics.shared.SharedFieldResolverTask
import semantics.shared.OEROccurrenceContext
import semantics.shared.RootFieldReferenceInvocationObservation
import semantics.shared.materialize
import semantics.shared.withAuthoritativeNodeId
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * Invokes and publishes one field for Resolver01-03 and Resolver06-08. Unlike a coroutine task,
 * it needs no launch-time scope, so the task itself also supplies its dispatch context.
 */
internal class DepthFirstFieldResolverTask(
    override val operationContext: DepthFirstOperationContext,
    override val oerOccurrenceContext: OEROccurrenceContext,
    override val selection: ObjectSelection,
    override val publicationCell: EngineResultCell,
    private val reference: RootFieldReferenceData? = null,
    private val invocationDemand: SelectionForest? = null,
    override val publicationPath: List<PathComponent> = oerOccurrenceContext.coordinate(selection.key),
    override val publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> = selection.key.field.outputType,
) : SharedFieldResolverTask, SharedFieldResolverContext, DepthFirstTask {
    override val publicationConstructionDemand get() = selection.subselections
    // List-element references sit deeper than the field whose output contains them.
    override val path get() = publicationPath.dropLast(1)
    private val operation get() = operationContext
    private val world get() = operation.world

    init {
        require(selection.key.field.containingDef == oerOccurrenceContext.target.type) {
            "Resolver selection does not belong to its target object"
        }
        // Claim before dispatch: the reactor may freeze the OER before this task runs.
        publicationCell.createValuePromise()
        publicationCell.setActivated(true)
    }

    /** Invokes one field, follows reference tails, and publishes its passively resolved output. */
    fun run(): Unit = context(operation, world) {
        val occurrence = oerOccurrenceContext
        val key = selection.groundKey()
        val invocationDemand = this.invocationDemand ?: operation.complete(publicationConstructionDemand)
        var value: ResolverOutputData? = reference ?: when (val arguments = key.arguments) {
            Arguments.Error -> {
                check(publicationCell.getValue().complete(ErrorEngineResult.of(EngineErrorData.of()))) {
                    "Cell value was completed twice"
                }
                return@context
            }
            is Arguments.Resolved -> {
                val resolver = world.resolverRegistry.resolver(key.field)
                val fragments = resolver.instantiateFragmentsAt(occurrence.root, publicationPath)
                val input = runBlocking {
                    // Sibling dependency order and depth-first dispatch make this input ready.
                    context(operation, CycleCheckState.createNOP()) {
                        occurrence.target.materialize(
                            selections = fragments.objectFragment.materializeSelections,
                            reader = publicationPath,
                        )
                    }
                }
                runBlocking {
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
        var nodeIdentity: NodeReferenceIdentity? = null
        while (value is RootFieldReferenceData) {
            val reference = value
            require(reference.conformsToResolverOutputSchemaType(publicationExpectedType)) {
                "Root-field reference does not conform to ${publicationExpectedType}"
            }
            nodeIdentity = nodeIdentity ?: reference.nodeReferenceIdentityOrNull()
            value = resolveRootFieldReference(
                reference, occurrence.root, publicationPath, invocationDemand,
            )
        }
        val result = operation.passiveValues.resolvePassiveValues(
            value = value.withAuthoritativeNodeId(nodeIdentity, invocationDemand),
            root = occurrence.root,
            expectedType = publicationExpectedType,
            path = publicationPath,
            constructionDemand = publicationConstructionDemand,
            invocationDemand = invocationDemand,
            parent = occurrence,
        )
        check(publicationCell.getValue().complete(result)) { "Cell value was completed twice" }
    }

    /** Invokes one independently rooted reference target using this resolver's Query-fragment policy. */
    private fun resolveRootFieldReference(
        reference: RootFieldReferenceData,
        publicationRoot: ObjectEngineResult,
        publicationPath: List<PathComponent>,
        invocationDemand: SelectionForest,
    ): ResolverOutputData? = context(operation, world) {
        val invocation = reference.prepareInvocation()
        val queryValue =
            resolveQueryFragment(
                queryFragment = invocation.fragments.queryFragment,
                coordinate = invocation.path,
            )
        val output =
            runBlocking {
                invocation.resolver(
                    input = invocation.emptyObjectInput(),
                    queryValue = queryValue,
                    arguments = reference.arguments,
                    selections = invocationDemand,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
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
        output
    }

    /**
     * Resolves a fresh Query OER recursively, with its own dispatcher, for both depth-first variants.
     * Its work cannot consume the enclosing passive traversal's accumulated fringe or reactor queue.
     */
    private fun resolveQueryFragment(
        queryFragment: ResolverFragment,
        coordinate: List<PathComponent>,
    ): EngineObjectData.Sync = context(operation, world) {
        if (queryFragment.constructionSelections.isEmpty()) {
            return@context engineObjectDataOf(world.schema.requireQueryTypeDef())
        }
        val queryResult = DepthFirstResolve(operation, operation.complete)
            .resolve(queryFragment.constructionSelections)
        operation.resolverObserver.onQueryFragmentResult(queryFragment.resolverOccurrenceId, queryResult)
        runBlocking {
            context(operation, CycleCheckState.createNOP()) {
                queryResult.materialize(queryFragment.materializeSelections, coordinate)
            }
        }
    }
}
