package semantics.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import model.SelectionForest
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext

/** Request state and grounded-demand policy for Resolver21-23. */
internal class CoroutineOperationContext(
    base: SharedOperationContext<*>,
    requestScope: CoroutineScope,
    val complete: (SelectionForest) -> SelectionForest,
    val supportsParentFields: Boolean,
    val cycleChecker: CycleCheckState,
) : SharedOperationContext<CoroutineTaskDispatcher>(base.world, base.variableBindingsState, base.resolverObserver) {
    override val dispatcher = CoroutineTaskDispatcher(requestScope)
    val passiveValues = CoroutineResolvePassiveValues(this)
}
