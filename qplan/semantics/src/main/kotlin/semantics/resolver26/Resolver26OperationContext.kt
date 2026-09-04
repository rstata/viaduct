package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import semantics.shared.CycleCheckState
import semantics.shared.OperationContext

/** Request-local state and observation boundary specific to Resolver26. */
internal class Resolver26OperationContext(
    base: OperationContext,
    val requestScope: CoroutineScope,
    override val resolverObserver: Resolver26Observer,
    val cycleChecker: CycleCheckState = CycleCheckState.create(),
    val bindingDeclarationsState: BindingDeclarationsState = BindingDeclarationsState(),
    val queryValuesState: QueryValuesState = QueryValuesState(),
) : OperationContext(
        world = base.world,
        variableBindingsState = base.variableBindingsState,
        resolverObserver = resolverObserver,
    )
