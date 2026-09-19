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
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver02 requires non-selective resolvers"
    }
    return DepthFirstResolve(
        operation = this@resolve,
        complete = { completedSelections ->
            completedSelections.successorBoundaryDemand(this@resolve)
        },
    ).resolve(selections)
}
