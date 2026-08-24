package semantics.resolver26

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.VariableBinding
import model.fetchBindings
import model.groundKey
import model.merge
import model.registry.VariableDefinition
import model.selectionForestOf
import model.variableArgumentNames
import model.variableSourceSelectionStamps

/**
 * Fills binding aliases, reads object-path providers, and installs every local field resolver.
 *
 * Each installation grounds its selection, completes argument bindings unlocked by grounding,
 * claims the exact target cell, and registers its writer for cycle detection. This function waits
 * for all installations before returning so the enclosing orchestration can freeze the target,
 * while the launched field-resolver tasks may continue afterward. Freezing the target without
 * waiting for installation would race with those installations reserving their cells.
 */
internal suspend fun ObjectOrchestrationTask.launchBindingsAndResolvers(
    closed: CloseInputDemandResult,
) {
    context(world, support) {
        coroutineScope {
            closed.bindingAliases.forEach { alias ->
                launch {
                    world.completeBinding(
                        alias.localizedVariable,
                        world.fetchBinding(alias.sourceVariable),
                    )
                }
            }
            closed.pathVariableDefinitions.forEach { definition ->
                launch {
                    val reader: List<PathComponent> =
                        requireNotNull(definition.variable.stamp)
                            .resolverPath
                    val binding: VariableBinding =
                        target.readProvider(
                            definition = definition,
                            reader = reader,
                            containingObjectPath = path,
                            support = support,
                        )
                    world.completeBinding(definition.variable, binding)
                }
            }
            closed.demand.byKey().forEach { (objectKey, selection) ->
                if (objectKey.field in world.resolverRegistry) {
                    launch {
                        installAndLaunchFieldResolver(
                            selection = selection,
                            expansion = closed.expansions.getValue(objectKey),
                        )
                    }
                }
            }
        }
    }
}

// Grounds one active selection, claims its exact target cell, and registers its writer.
// Launches a field-resolver task after completing any argument bindings unlocked by grounding.
private suspend fun ObjectOrchestrationTask.installAndLaunchFieldResolver(
    selection: ObjectSelection,
    expansion: ResolverExpansion,
) {
    context(world, support) {
        val variableArgumentCount = selection.key.arguments.variableArgumentNames().size
        val variableSourceSelectionStamps =
            selection.key.arguments.variableSourceSelectionStamps()
        val groundedSelection: ObjectSelection =
            selectionForestOf(selection)
                .merge(target.type)
                .fetchBindings()
                .byGroundKey()
                .values
                .single()
        val groundKey = groundedSelection.groundKey()
        check(groundKey.field in world.resolverRegistry) {
            "Resolver26 attempted to install passive key $groundKey"
        }
        completeFromArgumentBindings(expansion, groundKey)

        val cell = target.reserveCell(groundKey)
        cell.createValuePromise()
        support.registerWriter(
            cell = cell,
            writer = path + groundKey,
        )
        val fieldResolverTask =
            FieldResolverTask(
                world = world,
                support = support,
                path = path,
                groundedSelection = groundedSelection,
                resolver = expansion.resolver,
                inputMaterializeSelections = expansion.inputMaterializeSelections,
                target = target,
                cell = cell,
                variableArgumentCount = variableArgumentCount,
                variableSourceSelectionStamps = variableSourceSelectionStamps,
            )
        support.requestScope.launch {
            fieldResolverTask.run()
        }
    }
}

// Fills FromArgument bindings that were declared while their owning resolver key was still open.
// Bindings for owners that were already ground received their values during binding declaration.
private fun ObjectOrchestrationTask.completeFromArgumentBindings(
    expansion: ResolverExpansion,
    groundKey: ObjectEngineResult.GroundKey,
) {
    if (expansion.ownerKey is ObjectEngineResult.GroundKey) return
    expansion.variableDefinitions.forEach { stampedDefinition ->
        if (stampedDefinition.definition !is VariableDefinition.FromArgument) {
            return@forEach
        }
        val definition =
            stampedDefinition.definition as VariableDefinition.FromArgument
        world.completeBinding(
            stampedDefinition.variable,
            bindingFor(groundKey.arguments, definition),
        )
    }
}
