package semantics.resolver26

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.VariableBinding
import model.fetchBindings
import model.fetchGroundedArguments
import model.registry.VariableDefinition
import model.usedVariables
import model.variableArgumentNames

/**
 * Reads object-path providers and installs every local field resolver.
 *
 * Each installation resolves its invocation arguments, completes argument bindings unlocked by
 * that resolution, claims the original symbolic target cell, and registers its writer for cycle
 * detection. This function waits for all installations before returning so the enclosing
 * orchestration can freeze the target, while the launched field-resolver tasks may continue
 * afterward. Freezing the target without waiting for installation would race with those
 * installations reserving their cells.
 */
internal suspend fun ObjectOrchestrationTask.launchBindingsAndResolvers(
    closed: CloseInputDemandResult,
) {
    context(world, support) {
        coroutineScope {
            closed.pathVariableDefinitions.forEach { definition ->
                launch {
                    val reader: List<PathComponent> =
                        requireNotNull(definition.variable.stamp)
                            .resolverPath
                    val binding: VariableBinding =
                        target.readProvider(
                            definition = definition,
                            reader = reader,
                            support = support,
                        )
                    world.completeBinding(definition.variable, binding)
                }
            }
            val demandByKey = closed.demand.byKey()
            closed.expansions.forEach { (objectKey, expansion) ->
                launch {
                    installAndLaunchFieldResolver(
                        selection = demandByKey.getValue(objectKey),
                        expansion = expansion,
                    )
                }
            }
        }
    }
}

// Resolves one active selection's invocation arguments while retaining its symbolic cell key.
private suspend fun ObjectOrchestrationTask.installAndLaunchFieldResolver(
    selection: ObjectSelection,
    expansion: ResolverExpansion,
) {
    context(world, support) {
        val variableArgumentCount = selection.key.arguments.variableArgumentNames().size
        val variableResolverPaths =
            selection.key.arguments
                .usedVariables()
                .mapNotNullTo(linkedSetOf()) { variable -> variable.stamp?.resolverPath }
        val objectKey = selection.key
        val groundedArguments = objectKey.fetchGroundedArguments()
        check(objectKey.field in world.resolverRegistry) {
            "Resolver26 attempted to install passive key $objectKey"
        }
        check(!source.isPresent(objectKey.field.name)) {
            "Resolver26 attempted to install source-provided key $objectKey"
        }
        completeFromArgumentBindings(expansion, groundedArguments)

        val cell = target.reserveCell(objectKey)
        cell.createValuePromise()
        support.registerWriter(
            cell = cell,
            writer = path + objectKey,
        )
        val fieldResolverTask =
            FieldResolverTask(
                world = world,
                support = support,
                path = path,
                selection = selection,
                groundedArguments = groundedArguments,
                resolver = expansion.resolver,
                inputMaterializeSelections = expansion.inputMaterializeSelections,
                target = target,
                cell = cell,
                variableArgumentCount = variableArgumentCount,
                variableResolverPaths = variableResolverPaths,
            )
        support.requestScope.launch {
            fieldResolverTask.run()
        }
    }
}

// Fills FromArgument bindings that were declared while their owning resolver key was symbolic.
// Bindings for already-ground owners received their values during binding declaration.
private fun ObjectOrchestrationTask.completeFromArgumentBindings(
    expansion: ResolverExpansion,
    groundedArguments: model.Arguments.Ground,
) {
    if (expansion.ownerKey is ObjectEngineResult.GroundKey) return
    expansion.variableDefinitions.forEach { (variable, variableDefinition) ->
        if (variableDefinition !is VariableDefinition.FromArgument) {
            return@forEach
        }
        world.completeBinding(
            variable,
            bindingFor(groundedArguments, variableDefinition),
        )
    }
}
