package semantics.resolver26

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import model.Arguments
import model.Assumptions
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.requireQueryTypeDef
import model.SelectionForest
import model.VariableBinding
import model.merge
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
    private val lock = Any()
    private var accumulatedDemand: SelectionForest = initialDemand
    private val expandedKeys = linkedSetOf<ObjectEngineResult.ObjectKey>()
    private var prepared = false
    private var initialClosedDemand: CloseInputDemandResult? = null
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
        operation.objectOrchestrationState.register(this)
    }

    /**
     * Synchronously closes this object's demand and establishes its binding domain.
     * Returns the closed demand needed to materialize passive children before launch.
     */
    fun prepare(): ObjectSelectionForest =
        synchronized(lock) {
            check(!prepared) {
                "Resolver26 orchestration task at ${occurrence.path} was prepared twice"
            }
            val closed = closeNextDemand(initialDemand)
            prepared = true
            initialClosedDemand = closed
            operation.bindingDeclarationsState.markBindingsDeclared(occurrence.target)
            closed.demand
        }

    /**
     * Finishes synchronous orchestration after passive materialization.
     * Launches a coroutine only when provider reads or active field installation may suspend.
     */
    fun launch() {
        require(launched.compareAndSet(false, true)) {
            "Resolver26 orchestration task at ${occurrence.path} was launched twice"
        }
        val closed = synchronized(lock) {
            requireNotNull(initialClosedDemand) {
                "Resolver26 orchestration task at ${occurrence.path} launched before preparation"
            }
        }
        validatePassiveFields(closed)
        if (canReceiveParentDemand()) {
            dispatch(closed)
        } else if (closed.expansions.isNotEmpty() || closed.objectProviderReads.isNotEmpty()) {
            operation.requestScope.launch {
                this@ObjectOrchestrationTask.launchBindingsAndResolvers(closed)
                occurrence.target.freeze()
            }
        } else {
            occurrence.target.freeze()
        }
    }

    /** Adds exact demand discovered by a child occurrence after this task's initial preparation. */
    fun addDemand(demand: SelectionForest) {
        val closed =
            synchronized(lock) {
                check(prepared) {
                    "Resolver26 ancestor demand reached an unprepared orchestration task"
                }
                accumulatedDemand += demand
                closeNextDemand(demand).also { next ->
                    context(operation) {
                        source.materializePassiveFields(
                            occurrence = occurrence,
                            invocationDemand = next.demand,
                            closedDemand = next.demand,
                        )
                    }
                }
            }
        validatePassiveFields(closed)
        dispatch(closed)
    }

    private fun closeNextDemand(newDemand: SelectionForest): CloseInputDemandResult {
        val closed =
            context(world) {
                source.closeInputDemand(
                    occurrence = occurrence,
                    initialDemand = accumulatedDemand,
                    alreadyExpanded = expandedKeys,
                )
            }
        accumulatedDemand = closed.demand
        expandedKeys += closed.expansions.keys
        val newParentDemand =
            closed.expansions.values.fold(newDemand) { demand, expansion ->
                demand + expansion.inputDemand
            }
        context(operation) {
            declareBindings(closed)
            installParentFields(newParentDemand)
        }
        return closed
    }

    private fun dispatch(closed: CloseInputDemandResult) {
        if (closed.expansions.isEmpty() && closed.objectProviderReads.isEmpty()) return
        operation.requestScope.launch {
            this@ObjectOrchestrationTask.launchBindingsAndResolvers(closed)
        }
    }

    private fun canReceiveParentDemand(): Boolean =
        occurrence.target.type.fields.any { field ->
            operation.world.parentFieldRelations.containsValue(field)
        }

    internal fun freezeAtQuiescence() {
        if (canReceiveParentDemand()) occurrence.target.freeze()
    }

    // Checks that passive values selected by closed demand were installed before task dispatch.
    private fun validatePassiveFields(closed: CloseInputDemandResult) {
        synchronized(lock) {
            closed.demand.byKey().forEach { (objectKey, _) ->
                if (objectKey !in expandedKeys) {
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

    context(operation: Resolver26OperationContext)
    private fun installParentFields(newDemand: SelectionForest) {
        val parentSelections =
            newDemand.merge(occurrence.target.type).byKey().filterKeys { key ->
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
                "Parent field ${key.field.name} is not inverse to its containing producer occurrence"
            }
            if (!occurrence.target.isCellSet(key)) {
                occurrence.target.reserveCell(key).also { cell ->
                    cell.setValue(parent.target)
                    cell.setAccessResult(true)
                }
            }
            check(occurrence.target.getCell(key).getValue().get() === parent.target) {
                "Parent field ${key.field.name} does not reference its containing object occurrence"
            }
        }
        val parentTask = operation.objectOrchestrationState.task(parent.target)
        parentSelections.values.forEach { selection ->
            parentTask.addDemand(selection.subselections)
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
