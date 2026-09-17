package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext

/** Request-local state and observation boundary specific to Resolver26. */
internal class OperationContext(
    base: SharedOperationContext<*>,
    requestScope: CoroutineScope,
    override val resolverObserver: ResolverObserver,
    val cycleChecker: CycleCheckState = CycleCheckState.create(),
    val bindingDeclarationsState: BindingDeclarationsState = BindingDeclarationsState(),
) : SharedOperationContext<TaskDispatcher>(
        world = base.world,
        variableBindingsState = base.variableBindingsState,
        resolverObserver = resolverObserver,
    ) {
    /** Owns dispatch of the two permitted request-root task kinds. */
    override val dispatcher = TaskDispatcher(requestScope)
}
