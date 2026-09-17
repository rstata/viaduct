package semantics.resolvers.resolver03

import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver01.DepthFirstResolve
import semantics.resolvers.successorDemand
import semantics.shared.SharedOperationContext

/**
 * Resolves [selections] with selective resolver applications. Whether the results contain only the
 * necessary OER nodes has not been proved.
 */
context(operation: SharedOperationContext<*>)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(operation.selectiveResolvers) {
        "Resolver03 requires selective resolvers"
    }
    return DepthFirstResolve(
        operation = operation,
        complete = { completedSelections ->
            context(operation.world) {
                completedSelections.successorDemand()
            }
        },
    ).resolve(selections)
}
