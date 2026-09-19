package semantics.resolvers.resolver07

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver06.DepthFirstReactor
import semantics.resolvers.successorBoundaryDemand
import semantics.resolvers.resolver01.DepthFirstTask
import semantics.shared.SharedOperationContext

/**
 * Resolves [selections] through a depth-first work queue with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(operation: SharedOperationContext<*>)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(selections, onTaskStarted = {})

context(operation: SharedOperationContext<*>)
internal fun resolve(
    selections: SelectionForest,
    onTaskStarted: (DepthFirstTask) -> Unit,
): ObjectEngineResult {
    require(!operation.world.selectiveResolvers) {
        "Resolver07 requires non-selective resolvers"
    }
    val source = operation.world.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        operation = operation,
        complete = { completedSelections ->
            completedSelections.successorBoundaryDemand()
        },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
