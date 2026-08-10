package semantics.resolver22

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.registry.successorBoundaryDemand
import semantics.RuntimeSupport
import semantics.SelectionCompletion
import semantics.coroutineResolve

/**
 * Resolves [selections] through structured coroutines with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    require(!world.selectiveResolvers) {
        "Resolver22 requires non-selective resolvers"
    }
    val runtimeSupport =
        RuntimeSupport.cycleChecking { completedSelections ->
            SelectionCompletion(
                selections = completedSelections.successorBoundaryDemand(),
            )
        }
    return runBlocking {
        withTimeout(90_000) {
            context(runtimeSupport) {
                this@resolve.coroutineResolve(selections)
            }
        }
    }
}
