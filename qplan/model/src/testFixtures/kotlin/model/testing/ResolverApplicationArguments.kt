package model.testing

import model.Schema
import model.Value

/**
 * Arguments observed across all resolver applications in one schema-embedded test world.
 */
class ResolverApplicationArguments internal constructor() {
    private val argumentsByField =
        linkedMapOf<Schema.OutputField, MutableList<Value.Arguments>>()

    internal fun observer(
        also: CanonicalFieldResolverApplicationObserver?,
    ): CanonicalFieldResolverApplicationObserver =
        { field, input, arguments, demand ->
            synchronized(argumentsByField) {
                argumentsByField.getOrPut(field, ::mutableListOf).add(arguments)
            }
            also?.invoke(field, input, arguments, demand)
        }

    /** Returns the arguments in application order, including duplicate applications. */
    fun arguments(field: Schema.OutputField): List<Value.Arguments> =
        synchronized(argumentsByField) {
            argumentsByField[field].orEmpty().toList()
        }

    /** Returns an immutable snapshot of every field's arguments in application order. */
    fun all(): Map<Schema.OutputField, List<Value.Arguments>> =
        synchronized(argumentsByField) {
            argumentsByField.mapValuesTo(linkedMapOf()) { (_, arguments) ->
                arguments.toList()
            }
        }
}
