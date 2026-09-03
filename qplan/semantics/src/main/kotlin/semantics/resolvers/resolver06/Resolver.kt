package semantics.resolvers.resolver06

import model.ObjectEngineResult
import model.SelectionForest
import semantics.shared.OperationContext

/**
 * Resolves [selections] through a depth-first work queue when resolver object fragments are empty,
 * except for generated `T_V_A_Bridge.node` fragments that select passive sibling `id`. Results are
 * non-selective and may contain more OER nodes than are strictly necessary to resolve the query.
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
        "Resolver06 requires non-selective resolvers"
    }
    val source = operation.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        operation = operation,
        complete = { completedSelections -> completedSelections },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
