package semantics.resolvers.resolver08

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver06.DepthFirstReactor
import semantics.resolvers.successorDemand
import semantics.shared.OperationContext

/**
 * Resolves [selections] through a depth-first work queue with selective resolver applications.
 * Whether the results contain only the necessary OER nodes has not been proved.
 */
context(operation: OperationContext)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(selections, onTaskStarted = {})

context(operation: OperationContext)
internal fun resolve(
    selections: SelectionForest,
    onTaskStarted: (DepthFirstReactor.Task) -> Unit,
): ObjectEngineResult {
    require(operation.selectiveResolvers) {
        "Resolver08 requires selective resolvers"
    }
    val source = operation.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        operation = operation,
        complete = { completedSelections ->
            context(operation.world) {
                completedSelections.successorDemand()
            }
        },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
