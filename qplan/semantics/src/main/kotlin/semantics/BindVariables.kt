package semantics

import model.Arguments

import model.ObjectEngineResult

import model.Assumptions
import model.EngineInputData
import model.PathComponent
import model.registry.VariableDefinition

/**
 * Declares and immediately completes every argument-defined variable belonging to these resolver
 * occurrences.
 *
 * The exact resolver key completes the containing-object [path], so argument-distinct occurrences
 * of one resolver field define distinct stamped variables. Every occurrence must be declared and
 * completed exactly once.
 */
context(world: Assumptions)
internal fun Iterable<ObjectEngineResult.GroundKey>.bindFromArguments(
    path: List<PathComponent>,
    onDeclared: (
        Arguments.Variable,
        VariableDefinition.FromArgument,
    ) -> Unit = { _, _ -> },
    onCompleted: (
        Arguments.Variable,
        VariableDefinition.FromArgument,
        EngineInputData?,
    ) -> Unit = { _, _, _ -> },
) {
    forEach { key ->
        if (key.field !in world.resolverRegistry) return@forEach
        val arguments = key.arguments as? Arguments.Resolved ?: return@forEach

        world.resolverRegistry
            .resolver(key.field)
            .variables
            .forEach { (variable, definition) ->
                if (definition is VariableDefinition.FromArgument) {
                    val stamped = variable.stamp(path + key)
                    val value = definition.read(arguments)
                    onDeclared(stamped, definition)
                    world.declareBinding(stamped)
                    onCompleted(stamped, definition, value)
                    world.completeBinding(stamped, value)
                }
            }
    }
}
