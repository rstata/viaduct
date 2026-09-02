package semantics.resolver26

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import model.Arguments
import model.Assumptions
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.VariableBinding
import model.registry.VariableDefinition
import model.schemaType
import viaduct.engine.api.EngineObjectData

/** Installs and launches the work associated with one object-result occurrence. */
internal class ObjectOrchestrationTask(
    internal val world: Assumptions,
    internal val support: Resolver26Support,
    internal val root: ObjectEngineResult,
    internal val path: List<PathComponent>,
    internal val source: EngineObjectData.Sync,
    internal val target: ObjectEngineResult,
    private val initialDemand: SelectionForest,
) {
    private val closedDemand = AtomicReference<CloseInputDemandResult?>(null)
    private val launched = AtomicBoolean(false)

    /**
     * Synchronously closes this object's demand and establishes its binding domain.
     * Returns the closed demand needed to materialize passive children before launch.
     */
    fun prepare(): ObjectSelectionForest {
        require(source.schemaType == target.type) {
            "Source type ${source.schemaType.name} does not match result type ${target.type.name}"
        }

        val closed: CloseInputDemandResult =
            context(world) {
                source.closeInputDemand(
                    root = root,
                    path = path,
                    initialDemand = initialDemand,
                )
            }
        require(closedDemand.compareAndSet(null, closed)) {
            "Resolver26 orchestration task at $path was prepared twice"
        }
        context(world) {
            declareBindings(closed)
        }
        support.markBindingsDeclared(target)
        return closed.demand
    }

    /**
     * Finishes synchronous orchestration after passive materialization.
     * Launches a coroutine only when provider reads or active field installation may suspend.
     */
    fun launch() {
        require(launched.compareAndSet(false, true)) {
            "Resolver26 orchestration task at $path was launched twice"
        }
        val closed =
            requireNotNull(closedDemand.get()) {
                "Resolver26 orchestration task at $path launched before preparation"
            }
        validatePassiveFields(closed)

        if (closed.expansions.isNotEmpty()) {
            support.requestScope.launch {
                this@ObjectOrchestrationTask.launchBindingsAndResolvers(closed)
                target.freeze()
            }
        } else {
            target.freeze()
        }
    }

    // Checks that passive values selected by closed demand were installed before task dispatch.
    private fun validatePassiveFields(closed: CloseInputDemandResult) {
        closed.demand.byKey().forEach { (objectKey, _) ->
            if (objectKey !in closed.expansions) {
                check(
                    objectKey is ObjectEngineResult.GroundKey &&
                        target.isCellSet(objectKey),
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
context(world: Assumptions)
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
                        world.bindVariable(
                            variableId,
                            bindingFor(expansion.ownerKey.arguments, definition),
                        )
                    } else {
                        world.declareBinding(variableId)
                    }

                is VariableDefinition.FromObjectField,
                is VariableDefinition.FromQueryField,
                -> Unit
            }
        }
    }
    closed.pathVariableDefinitions.forEach { read ->
        world.declareBinding(requireNotNull(read.definition.variable.instanceId))
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
