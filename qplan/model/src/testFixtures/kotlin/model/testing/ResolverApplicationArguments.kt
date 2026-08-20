package model.testing

import viaduct.graphql.schema.ViaductSchema

import model.Arguments


/**
 * Arguments observed across all resolver applications in one schema-embedded test world.
 */
class ResolverApplicationArguments internal constructor() {
    private val argumentsByField =
        linkedMapOf<ViaductSchema.Field, MutableList<Arguments.Resolved>>()

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
    fun arguments(field: ViaductSchema.Field): List<Arguments.Resolved> =
        synchronized(argumentsByField) {
            argumentsByField[field].orEmpty().toList()
        }

    /** Returns an immutable snapshot of every field's arguments in application order. */
    fun all(): Map<ViaductSchema.Field, List<Arguments.Resolved>> =
        synchronized(argumentsByField) {
            argumentsByField.mapValuesTo(linkedMapOf()) { (_, arguments) ->
                arguments.toList()
            }
        }
}
