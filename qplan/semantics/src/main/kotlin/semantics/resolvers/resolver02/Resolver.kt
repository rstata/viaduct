package semantics.resolvers.resolver02

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver01.DepthFirstResolve
import semantics.resolvers.successorBoundaryDemand
import semantics.shared.SharedOperationContext

/**
 * Resolves [selections] with non-selective resolver applications. Results may contain more OER
 * nodes than are strictly necessary to resolve the query.
 */
context(operation: SharedOperationContext<*>)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(!operation.selectiveResolvers) {
        "Resolver02 requires non-selective resolvers"
    }
    return DepthFirstResolve(
        operation = operation,
        complete = { completedSelections ->
            context(operation.world) {
                completedSelections.successorBoundaryDemand()
            }
        },
    ).resolve(selections)
}
