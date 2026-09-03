package semantics.resolvers.resolver03

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.registry.successorDemand
import semantics.resolvers.resolver01.DepthFirstResolve

/**
 * Resolves [selections] with selective resolver applications. Whether the results contain only the
 * necessary OER nodes has not been proved.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver03 requires selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    return DepthFirstResolve(
        world = world,
        complete = { completedSelections ->
            completedSelections.successorDemand()
        },
    ).resolve(source, selections)
}
