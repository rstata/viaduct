package semantics.shared

import model.Assumptions

/** Shared configuration, mutable state, and observation boundary for one resolution operation. */
open class OperationContext(
    val world: Assumptions,
    val variableBindingsState: VariableBindingsState = VariableBindingsState(),
    open val resolverObserver: ResolverObserver = ResolverObserver.createNOP(),
) {
    val schema
        get() = world.schema

    val resolverRegistry
        get() = world.resolverRegistry

    val selectiveResolvers
        get() = world.selectiveResolvers
}
