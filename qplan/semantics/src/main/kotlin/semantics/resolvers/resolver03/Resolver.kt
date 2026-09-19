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
fun SharedOperationContext<*>.resolve(selections: SelectionForest): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver03 requires selective resolvers"
    }
    return DepthFirstResolve(
        operation = this@resolve,
        complete = { completedSelections ->
            completedSelections.successorDemand(this@resolve)
        },
    ).resolve(selections)
}
