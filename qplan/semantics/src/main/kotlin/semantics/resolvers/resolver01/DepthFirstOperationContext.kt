package semantics.resolvers.resolver01

import model.SelectionForest
import semantics.shared.SharedOperationContext
import semantics.shared.SharedTaskDispatcher

/** Stable demand policy and scheduling capability for both depth-first implementations. */
internal class DepthFirstOperationContext(
    operation: SharedOperationContext<*>,
    val complete: (SelectionForest) -> SelectionForest,
    override val dispatcher: SharedTaskDispatcher<DepthFirstOrchestrationTask, DepthFirstFieldResolverTask>,
) : SharedOperationContext<SharedTaskDispatcher<DepthFirstOrchestrationTask, DepthFirstFieldResolverTask>>(
        operation.world, operation.variableBindingsState, operation.resolverObserver,
    ) {
    val passiveValues = DepthFirstPassiveValues(this)
}
