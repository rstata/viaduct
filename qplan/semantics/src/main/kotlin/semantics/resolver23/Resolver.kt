package semantics.resolver23

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.registry.successorDemand
import semantics.ResolverSupport
import semantics.coroutineResolve

/**
 * Resolves [selections] through structured coroutines with selective resolver applications. Whether
 * the results contain only the necessary OER nodes has not been proved.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver23 requires selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val resolverSupport =
        ResolverSupport.cycleChecking { completedSelections ->
            completedSelections.successorDemand()
        }
    return runBlocking {
        withTimeout(90_000) {
            context(resolverSupport) {
                source.coroutineResolve(selections)
            }
        }
    }
}
