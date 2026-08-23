package semantics.resolver08

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.registry.successorDemand
import semantics.DepthFirstReactor
import semantics.ReactorEventObserver
import semantics.ResolverSupport

/**
 * Resolves [selections] through a depth-first work queue with selective resolver applications.
 * Whether the results contain only the necessary OER nodes has not been proved.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(selections, eventObserver = {})

context(world: Assumptions)
internal fun resolve(
    selections: SelectionForest,
    eventObserver: ReactorEventObserver,
): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver08 requires selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val resolverSupport =
        ResolverSupport.noCycleChecking { selections ->
            selections.successorDemand()
        }
    return context(resolverSupport) {
        DepthFirstReactor(
            source = source,
            selections = selections,
            eventObserver = eventObserver,
        ).resolve()
    }
}
