package semantics.resolvers.resolver01

import model.ObjectEngineResult
import model.SelectionForest
import semantics.shared.OperationContext

/**
 * Resolves [selections] when resolver object fragments are empty. Results are non-selective and
 * may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(operation: OperationContext)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(!operation.selectiveResolvers) {
        "Resolver01 requires non-selective resolvers"
    }
    val source = operation.resolverRegistry.createRootQueryInput()
    return DepthFirstResolve(
        operation = operation,
        complete = { completedSelections -> completedSelections },
    ).resolve(source, selections)
}
