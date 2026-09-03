package semantics.resolvers.resolver02

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver01.DepthFirstResolve
import semantics.resolvers.successorBoundaryDemand
import semantics.shared.OperationContext

/**
 * Resolves [selections] with non-selective resolver applications. Results may contain more OER
 * nodes than are strictly necessary to resolve the query.
 */
context(operation: OperationContext)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(!operation.selectiveResolvers) {
        "Resolver02 requires non-selective resolvers"
    }
    val source = operation.resolverRegistry.createRootQueryInput()
    return DepthFirstResolve(
        operation = operation,
        complete = { completedSelections ->
            context(operation.world) {
                completedSelections.successorBoundaryDemand()
            }
        },
    ).resolve(source, selections)
}
