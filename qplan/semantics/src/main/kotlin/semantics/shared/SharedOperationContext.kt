package semantics.shared

import model.Assumptions

/**
 * Structurally immutable bundle of stable references for one semantics operation.
 * [D] preserves the dispatcher's task types; semantic operations that do not dispatch use `SharedOperationContext<*>`.
 */
open class SharedOperationContext<out D : SharedTaskDispatcher<*, *>> protected constructor(
    val world: Assumptions,
    val variableBindingsState: VariableBindingsState = VariableBindingsState(),
    open val resolverObserver: SharedResolverObserver = SharedResolverObserver.createNOP(),
) {
    /**
     * Execution implementations supply their concrete dispatcher. Standalone semantic operations
     * need no dispatcher and fail if one is requested.
     */
    internal open val dispatcher: D
        get() = error("This operation does not dispatch resolver tasks")

    companion object {
        /** Creates a standalone semantics context without requiring a dispatcher type at the call site. */
        operator fun invoke(
            world: Assumptions,
            variableBindingsState: VariableBindingsState = VariableBindingsState(),
            resolverObserver: SharedResolverObserver = SharedResolverObserver.createNOP(),
        ): SharedOperationContext<*> =
            SharedOperationContext<Nothing>(world, variableBindingsState, resolverObserver)
    }

    val schema
        get() = world.schema

    val resolverRegistry
        get() = world.resolverRegistry

    val selectiveResolvers
        get() = world.selectiveResolvers
}
