package semantics.resolver06

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import semantics.DepthFirstReactor
import semantics.ReactorEventObserver
import semantics.RuntimeSupport

/**
 * Resolves [selections] through a depth-first work queue when resolver object fragments are empty,
 * except for generated `T$Bridge.$node` fragments that select passive sibling `$id`. Results are
 * non-selective and may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): EngineResult.Object =
    resolve(selections, eventObserver = {})

context(world: Assumptions)
internal fun resolve(
    selections: SelectionForest,
    eventObserver: ReactorEventObserver,
): EngineResult.Object {
    require(!world.selectiveResolvers) {
        "Resolver06 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.resolveRootQuery()
    val runtimeSupport =
        RuntimeSupport { selections -> selections }
    return context(runtimeSupport) {
        DepthFirstReactor(
            source = source,
            selections = selections,
            eventObserver = eventObserver,
        ).resolve()
    }
}
