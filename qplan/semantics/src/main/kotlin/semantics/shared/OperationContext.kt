package semantics.shared

import model.Assumptions

/** Structurally immutable bundle of stable references for one semantics operation. */
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
