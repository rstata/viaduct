package semantics.resolvers.resolver22

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.registry.successorBoundaryDemand
import semantics.resolvers.resolver21.CoroutineResolve

/**
 * Resolves [selections] through structured coroutines with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver22 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val resolver =
        CoroutineResolve(
            world = world,
            complete = { completedSelections ->
                completedSelections.successorBoundaryDemand()
            },
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
