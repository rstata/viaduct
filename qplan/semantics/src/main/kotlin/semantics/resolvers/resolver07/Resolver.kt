package semantics.resolvers.resolver07

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.registry.successorBoundaryDemand
import semantics.resolvers.resolver06.DepthFirstReactor

/**
 * Resolves [selections] through a depth-first work queue with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(selections, onTaskStarted = {})

context(world: Assumptions)
internal fun resolve(
    selections: SelectionForest,
    onTaskStarted: (DepthFirstReactor.Task) -> Unit,
): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver07 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        world = world,
        complete = { completedSelections ->
            completedSelections.successorBoundaryDemand()
        },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
