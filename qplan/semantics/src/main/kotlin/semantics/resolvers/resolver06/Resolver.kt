package semantics.resolvers.resolver06

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest

/**
 * Resolves [selections] through a depth-first work queue when resolver object fragments are empty,
 * except for generated `T_V_A_Bridge.node` fragments that select passive sibling `id`. Results are
 * non-selective and may contain more OER nodes than are strictly necessary to resolve the query.
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
        "Resolver06 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    return DepthFirstReactor(
        world = world,
        complete = { completedSelections -> completedSelections },
        source = source,
        selections = selections,
        onTaskStarted = onTaskStarted,
    ).resolve()
}
