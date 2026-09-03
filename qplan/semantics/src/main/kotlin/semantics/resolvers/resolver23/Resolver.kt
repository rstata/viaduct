package semantics.resolvers.resolver23

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.registry.successorDemand
import semantics.resolvers.resolver21.CoroutineResolve

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
    val resolver =
        CoroutineResolve(
            world = world,
            complete = { completedSelections ->
                completedSelections.successorDemand()
            },
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
