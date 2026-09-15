package semantics.resolver26

import java.util.concurrent.atomic.AtomicBoolean
import model.Arguments
import model.Assumptions
import model.InclusionCondition
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.SelectionForest
import model.VariableBinding
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import model.schemaType
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedOrchestrationTask
import semantics.shared.SharedOperationContext
import semantics.shared.installParentBackedgeFields
import viaduct.engine.api.EngineObjectData

/**
 * Installs and launches the work associated with one object-result occurrence.
 *
 * An occurrence with active work retains a request-root coroutine as an architectural placeholder
 * for future asynchronous orchestration. The current orchestration body does not suspend.
 */
internal class OrchestrationTask private constructor(
    internal val operation: OperationContext,
    override val occurrence: OEROccurrenceContext,
    override val source: EngineObjectData.Sync,
) : SharedOrchestrationTask {
    internal val world: Assumptions = operation.world
    private lateinit var closed: CloseInputDemandResult
    override val closedDemand: ObjectSelectionForest get() = closed.demand
    private val launched = AtomicBoolean(false)

    init {
        require(occurrence.root.type == operation.schema.requireQueryTypeDef()) {
            "Resolver26 occurrence root must have Query type"
        }
        require(occurrence.path.isEmpty() == (occurrence.root === occurrence.target)) {
            "Only a root Resolver26 occurrence may use its root as its target"
        }
        require(source.schemaType == occurrence.target.type) {
            "Source type ${source.schemaType.name} does not match result type ${occurrence.target.type.name}"
        }
    }

    companion object {
        /** Creates a fully prepared task without dispatching its active work. */
        fun create(
            operation: OperationContext,
            occurrence: OEROccurrenceContext,
            source: EngineObjectData.Sync,
            initialDemand: SelectionForest,
        ): OrchestrationTask =
            OrchestrationTask(operation, occurrence, source).apply {
                closed = context(world) { source.closeInputDemand(occurrence, initialDemand) }
                context(operation) {
                    declareBindings(closed)
                    occurrence.installParentBackedgeFields(closed.demand.byKey().keys.filterIsInstance<ObjectEngineResult.ParentKey>())
                }
                operation.bindingDeclarationsState.markBindingsDeclared(occurrence.target)
            }
    }

    internal val hasActiveWork: Boolean
        get() = closed.fieldResolverOccurrenceContexts.isNotEmpty() ||
            closed.rootFieldReferenceOccurrences.isNotEmpty() ||
            closed.objectProviderReadsByResolverOccurrence.values.any { it.isNotEmpty() }

    /** Checks the one-shot dispatch boundary before entering the request-root coroutine. */
    internal fun checkDispatch() {
        require(launched.compareAndSet(false, true)) {
            "Resolver26 orchestration task at ${occurrence.path} was dispatched twice"
        }
        validatePassiveFields(closed)
    }

    /** Installs field tasks and seals this object's field set. */
    internal fun run() {
        FieldResolverTask.launchAll(this, closed)
        occurrence.target.freeze()
    }

    // Checks that passive values selected by closed demand were installed before task dispatch.
    private fun validatePassiveFields(closed: CloseInputDemandResult) {
        closed.demand.byKey().forEach { (objectKey, selection) ->
            if (selection.inclusionCondition === InclusionCondition.Never) {
                return@forEach
            }
            if (
                objectKey !in closed.fieldResolverOccurrenceContexts &&
                objectKey !in closed.rootFieldReferenceOccurrences
            ) {
                check(
                    objectKey is ObjectEngineResult.GroundKey &&
                        occurrence.target.isCellSet(objectKey),
                ) {
                    "Resolver26 passive key $objectKey was not materialized by " +
                        "resolvePassiveValues"
                }
            }
        }
    }
}

// Adds every binding introduced by the closed demand to the world's binding domain.
// Grounded argument bindings receive values immediately; open and provider bindings remain pending.
context(operation: SharedOperationContext<*>)
private fun declareBindings(closed: CloseInputDemandResult) {
    check(!closed.bindingDeclarationStarted) {
        "Resolver26 closed demand attempted to declare its bindings twice"
    }
    closed.bindingDeclarationStarted = true
    closed.fieldResolverOccurrenceContexts.values
        .forEach { fieldResolverOccurrenceContext ->
        val ownerKey = fieldResolverOccurrenceContext.selection.key
        fieldResolverOccurrenceContext.variableDefinitions.forEach { variableDefinition ->
            val variableId = requireNotNull(variableDefinition.variable.instanceId)
            when (val definition = variableDefinition.definition) {
                VariableDefinition.FromProvider ->
                    operation.variableBindingsState.declareBinding(variableId)

                is VariableDefinition.FromArgument ->
                    if (ownerKey is ObjectEngineResult.GroundKey) {
                        operation.variableBindingsState.bindVariable(
                            variableId,
                            bindingFor(ownerKey.arguments, definition),
                        )
                    } else {
                        operation.variableBindingsState.declareBinding(variableId)
                    }

                is VariableDefinition.FromField -> Unit
            }
        }
    }
    closed.objectProviderReadsByResolverOccurrence.values.flatten().forEach { read ->
        operation.variableBindingsState.declareBinding(
            requireNotNull(read.definition.variable.instanceId),
        )
    }
    closed.fieldResolverOccurrenceContexts.values
        .flatMap { context -> context.fragments.queryFragment.pathVariableDefinitions }
        .forEach { definition ->
            operation.variableBindingsState.declareBinding(
                requireNotNull(definition.variable.instanceId),
            )
        }
}

// Reads one FromArgument definition from grounded arguments while preserving argument errors.
internal fun bindingFor(
    arguments: Arguments.Ground,
    definition: VariableDefinition.FromArgument,
): VariableBinding =
    when (arguments) {
        Arguments.Error -> VariableBinding.Error
        is Arguments.Resolved -> VariableBinding.of(definition.read(arguments))
    }
