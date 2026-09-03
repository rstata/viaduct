package semantics.resolvers.resolver08

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.registry.successorDemand
import semantics.resolvers.resolver06.DepthFirstReactor

/**
 * Resolves [selections] through a depth-first work queue with selective resolver applications.
 * Whether the results contain only the necessary OER nodes has not been proved.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(selections, onTaskStarted = {})

context(world: Assumptions)
internal fun resolve(
    selections: SelectionForest,
    onTaskStarted: (DepthFirstReactor.Task) -> Unit,
): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver08 requires selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        world = world,
        complete = { completedSelections ->
            completedSelections.successorDemand()
        },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
