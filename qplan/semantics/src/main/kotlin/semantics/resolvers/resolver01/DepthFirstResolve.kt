package semantics.resolvers.resolver01

import model.ObjectEngineResult
import model.SelectionForest
import model.requireQueryTypeDef
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedOperationContext

/** Resolver01-03's recursive driver; orchestration and field tasks are shared with Resolver06-08. */
internal class DepthFirstResolve(
    operation: SharedOperationContext<*>,
    complete: (SelectionForest) -> SelectionForest,
) {
    private val dispatcher = DepthFirstTaskDispatcher()
    private val operation = DepthFirstOperationContext(operation, complete, dispatcher)

    /** Dispatches orchestration for a fresh Query root and resolves its accumulated fringe. */
    fun resolve(selections: SelectionForest): ObjectEngineResult {
        val source = operation.resolverRegistry.createRootQueryInput()
        val result = ObjectEngineResult.of(operation.schema.requireQueryTypeDef(), mutable = true)
        dispatcher.dispatchOrchestrator(
            DepthFirstOrchestrationTask.create(
                operation = operation,
                occurrence = OEROccurrenceContext(result, emptyList(), result),
                source = source,
                constructionDemand = selections,
            ),
        )
        dispatcher.resolveOrchestrators()
        return result
    }
}
