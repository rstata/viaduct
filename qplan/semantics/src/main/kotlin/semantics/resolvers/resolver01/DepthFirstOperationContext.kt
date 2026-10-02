package semantics.resolvers.resolver01

import model.SelectionForest
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.shared.SharedOperationContext
import semantics.shared.SharedTaskDispatcher

/** One depth-first resolution operation: shared state, demand policy, scheduling, and passive traversal. */
internal class DepthFirstOperationContext(
    operation: SharedOperationContext<*>,
    val complete: (SelectionForest) -> SelectionForest,
    dispatcher: DepthFirstDispatcher,
) : SharedOperationContext<DepthFirstDispatcher> by SharedOperationContext.create(
        world = operation.world,
        variableBindings = operation.variableBindings,
        resolverObserver = operation.resolverObserver,
        dispatcher = dispatcher,
    ) {
    val passiveValues = DepthFirstPassiveValueResolutionLogic(this)
}

/** Dispatcher contract shared by recursive and queued depth-first execution. */
internal typealias DepthFirstDispatcher = SharedTaskDispatcher<
    DepthFirstOrchestrationTask,
    GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
>
