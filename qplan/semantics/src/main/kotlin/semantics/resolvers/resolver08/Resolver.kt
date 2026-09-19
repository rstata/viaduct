package semantics.resolvers.resolver08

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver06.DepthFirstReactor
import semantics.resolvers.successorDemand
import semantics.resolvers.resolver01.DepthFirstTask
import semantics.shared.SharedOperationContext

/**
 * Resolves [selections] through a depth-first work queue with selective resolver applications.
 * Whether the results contain only the necessary OER nodes has not been proved.
 */
context(operation: SharedOperationContext<*>)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(selections, onTaskStarted = {})

context(operation: SharedOperationContext<*>)
internal fun resolve(
    selections: SelectionForest,
    onTaskStarted: (DepthFirstTask) -> Unit,
): ObjectEngineResult {
    require(operation.world.selectiveResolvers) {
        "Resolver08 requires selective resolvers"
    }
    val source = operation.world.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        operation = operation,
        complete = { completedSelections ->
            completedSelections.successorDemand()
        },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
