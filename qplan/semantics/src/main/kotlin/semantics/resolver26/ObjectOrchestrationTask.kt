package semantics.resolver26

import model.Arguments
import model.Assumptions
import model.ObjectEngineResult
import model.PathComponent
import model.SelectionForest
import model.VariableBinding
import model.registry.VariableDefinition
import model.schemaType
import viaduct.engine.api.EngineObjectData

/** Closes, installs, and freezes the work associated with one object-result occurrence. */
internal class ObjectOrchestrationTask(
    internal val world: Assumptions,
    internal val support: Resolver26Support,
    internal val path: List<PathComponent>,
    internal val source: EngineObjectData.Sync,
    internal val target: ObjectEngineResult,
    private val initialDemand: SelectionForest,
) {
    suspend fun run() {
        require(source.schemaType == target.type) {
            "Source type ${source.schemaType.name} does not match result type ${target.type.name}"
        }

        val closed: CloseInputDemandResult = closeInputDemand(initialDemand)
        declareBindings(closed)
        support.markBindingsDeclared(target)
        materializePassiveFields(closed.demand)
        launchBindingsAndResolvers(closed)
        target.freeze()
    }
}

// Adds every binding introduced by the closed demand to the world's binding domain.
// Grounded argument bindings receive values immediately; open and provider bindings remain pending.
private fun ObjectOrchestrationTask.declareBindings(closed: CloseInputDemandResult) {
    check(!closed.bindingDeclarationStarted) {
        "Resolver26 closed demand attempted to declare its bindings twice"
    }
    closed.bindingDeclarationStarted = true
    closed.bindingAliases.forEach { alias ->
        world.declareBinding(alias.localizedVariable)
    }
    closed.expansions.values.forEach { expansion ->
        expansion.variableDefinitions.forEach { stampedDefinition ->
            when (val definition = stampedDefinition.definition) {
                is VariableDefinition.FromArgument ->
                    if (expansion.ownerKey is ObjectEngineResult.GroundKey) {
                        world.bindVariable(
                            stampedDefinition.variable,
                            bindingFor(expansion.ownerKey.arguments, definition),
                        )
                    } else {
                        world.declareBinding(stampedDefinition.variable)
                    }

                is VariableDefinition.FromObjectField ->
                    world.declareBinding(stampedDefinition.variable)
            }
        }
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
