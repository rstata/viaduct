package semantics.resolvers

import model.Arguments

import model.ObjectEngineResult

import model.PathComponent
import model.ResolverOccurrenceId
import model.registry.VariableDefinition
import semantics.shared.OperationContext

/**
 * Declares and immediately completes every argument-defined variable belonging to these resolver
 * occurrences.
 *
 * The exact resolver key completes the containing-object [path], so argument-distinct occurrences
 * of one resolver field define distinct variable instances. Every occurrence must be declared and
 * completed exactly once.
 */
context(operation: OperationContext)
internal fun Iterable<ObjectEngineResult.GroundKey>.bindFromArguments(
    root: ObjectEngineResult,
    path: List<PathComponent>,
) {
    forEach { key ->
        if (key.field !in operation.resolverRegistry) return@forEach
        val arguments = key.arguments as? Arguments.Resolved ?: return@forEach

        operation.resolverRegistry
            .resolver(key.field)
            .variables
            .forEach { (variable, definition) ->
                if (definition is VariableDefinition.FromArgument) {
                    val instantiated =
                        variable.instantiate(
                            ResolverOccurrenceId.at(root, path + key),
                        )
                    val variableId = requireNotNull(instantiated.instanceId)
                    val value = definition.read(arguments)
                    operation.variableBindingsState.declareBinding(variableId)
                    operation.variableBindingsState.completeBinding(variableId, value)
                }
            }
    }
}
