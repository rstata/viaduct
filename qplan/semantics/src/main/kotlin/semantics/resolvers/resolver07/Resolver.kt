package semantics.resolvers.resolver07

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver06.DepthFirstReactor
import semantics.resolvers.successorBoundaryDemand
import semantics.shared.OperationContext

/**
 * Resolves [selections] through a depth-first work queue with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(operation: OperationContext)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(selections, onTaskStarted = {})

context(operation: OperationContext)
internal fun resolve(
    selections: SelectionForest,
    onTaskStarted: (DepthFirstReactor.Task) -> Unit,
): ObjectEngineResult {
    require(!operation.selectiveResolvers) {
        "Resolver07 requires non-selective resolvers"
    }
    val source = operation.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        operation = operation,
        complete = { completedSelections ->
            context(operation.world) {
                completedSelections.successorBoundaryDemand()
            }
        },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
