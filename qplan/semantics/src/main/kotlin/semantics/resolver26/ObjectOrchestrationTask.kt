package semantics.resolver26

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import model.Arguments
import model.Assumptions
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.requireQueryTypeDef
import model.SelectionForest
import model.VariableBinding
import model.registry.VariableDefinition
import model.schemaType
import viaduct.engine.api.EngineObjectData
import semantics.shared.OperationContext

/** Installs and launches the work associated with one object-result occurrence. */
internal class ObjectOrchestrationTask(
    internal val operation: Resolver26OperationContext,
    internal val occurrence: OEROccurrenceContext,
    internal val source: EngineObjectData.Sync,
    private val initialDemand: SelectionForest,
) {
    internal val world: Assumptions = operation.world
    private val closedDemand = AtomicReference<CloseInputDemandResult?>(null)
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

    /**
     * Synchronously closes this object's demand and establishes its binding domain.
     * Returns the closed demand needed to materialize passive children before launch.
     */
    fun prepare(): ObjectSelectionForest {
        val closed: CloseInputDemandResult =
            context(world) {
                source.closeInputDemand(
                    occurrence = occurrence,
                    initialDemand = initialDemand,
                )
            }
        require(closedDemand.compareAndSet(null, closed)) {
            "Resolver26 orchestration task at ${occurrence.path} was prepared twice"
        }
        context(operation) {
            declareBindings(closed)
            installParentFields(closed.demand)
        }
        operation.bindingDeclarationsState.markBindingsDeclared(occurrence.target)
        return closed.demand
    }

    /**
     * Finishes synchronous orchestration after passive materialization.
     * Launches a coroutine only when provider reads or active field installation may suspend.
     */
    fun launch() {
        require(launched.compareAndSet(false, true)) {
            "Resolver26 orchestration task at ${occurrence.path} was launched twice"
        }
        val closed =
            requireNotNull(closedDemand.get()) {
                "Resolver26 orchestration task at ${occurrence.path} launched before preparation"
            }
        validatePassiveFields(closed)

        if (closed.expansions.isNotEmpty()) {
            operation.requestScope.launch {
                this@ObjectOrchestrationTask.launchBindingsAndResolvers(closed)
                occurrence.target.freeze()
            }
        } else {
            occurrence.target.freeze()
        }
    }

    // Checks that passive values selected by closed demand were installed before task dispatch.
    private fun validatePassiveFields(closed: CloseInputDemandResult) {
        closed.demand.byKey().forEach { (objectKey, _) ->
            if (objectKey !in closed.expansions) {
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

    context(operation: Resolver26OperationContext)
    private fun installParentFields(closedDemand: ObjectSelectionForest) {
        val parentSelections =
            closedDemand.byKey().filterKeys { key ->
                key is ObjectEngineResult.ParentKey
            }
        if (parentSelections.isEmpty()) return
        val parent =
            occurrence.parent
                ?: error("Parent demand at ${occurrence.path} has no containing occurrence")
        val producer =
            occurrence.path
                .filterIsInstance<ObjectEngineResult.ObjectKey>()
                .lastOrNull()
                ?.field
        parentSelections.keys.forEach { objectKey ->
            val key = objectKey as ObjectEngineResult.ParentKey
            require(world.parentFieldRelations[key.field] == producer) {
                "Parent field ${key.field.containingDef.name}.${key.field.name} maps to " +
                    "${world.parentFieldRelations[key.field]}, not containing producer $producer " +
                    "at ${occurrence.path}"
            }
            occurrence.target.reserveCell(key).also { cell ->
                cell.setValue(parent.target)
            }
            check(occurrence.target.getCell(key).getValue().get() === parent.target) {
                "Parent field ${key.field.name} does not reference its containing object occurrence"
            }
        }
    }
}

// Adds every binding introduced by the closed demand to the world's binding domain.
// Grounded argument bindings receive values immediately; open and provider bindings remain pending.
context(operation: OperationContext)
private fun declareBindings(closed: CloseInputDemandResult) {
    check(!closed.bindingDeclarationStarted) {
        "Resolver26 closed demand attempted to declare its bindings twice"
    }
    closed.bindingDeclarationStarted = true
    closed.expansions.values.forEach { expansion ->
        expansion.variableDefinitions.forEach { variableDefinition ->
            val variableId = requireNotNull(variableDefinition.variable.instanceId)
            when (val definition = variableDefinition.definition) {
                is VariableDefinition.FromArgument ->
                    if (expansion.ownerKey is ObjectEngineResult.GroundKey) {
                        operation.variableBindingsState.bindVariable(
                            variableId,
                            bindingFor(expansion.ownerKey.arguments, definition),
                        )
                    } else {
                        operation.variableBindingsState.declareBinding(variableId)
                    }

                is VariableDefinition.FromField -> Unit
            }
        }
    }
    closed.objectProviderReads.forEach { read ->
        operation.variableBindingsState.declareBinding(
            requireNotNull(read.definition.variable.instanceId),
        )
    }
    closed.expansions.values
        .flatMap { expansion -> expansion.fragments.queryFragment.pathVariableDefinitions }
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
