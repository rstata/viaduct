package semantics.resolver07

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.registry.successorBoundaryDemand
import semantics.DepthFirstReactor
import semantics.ReactorEventObserver
import semantics.RuntimeSupport

/**
 * Resolves [selections] through a depth-first work queue with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
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
        "Resolver07 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.resolveRootQuery()
    val runtimeSupport =
        RuntimeSupport { selections ->
            selections.successorBoundaryDemand()
        }
    return context(runtimeSupport) {
        DepthFirstReactor(
            source = source,
            selections = selections,
            eventObserver = eventObserver,
        ).resolve()
    }
}
