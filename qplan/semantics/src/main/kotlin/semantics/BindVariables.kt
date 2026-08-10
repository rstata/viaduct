package semantics

import model.Assumptions
import model.PathComponent
import model.Value
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
internal fun Iterable<Value.GroundKey>.bindFromArguments(path: List<PathComponent>) {
    forEach { key ->
        if (key.field !in world.resolverRegistry) return@forEach

        world.resolverRegistry
            .resolver(key.field)
            .variables
            .forEach { (variable, definition) ->
                if (definition is VariableDefinition.FromArgument) {
                    val stamped = variable.stamp(path + key)
                    val value =
                        key.arguments.fieldValues.getValue(
                            definition.argument.argumentName,
                        )
                    world.declareBinding(stamped)
                    world.completeBinding(stamped, value)
                }
            }
    }
}
