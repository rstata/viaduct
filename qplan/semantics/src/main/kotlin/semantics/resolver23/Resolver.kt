package semantics.resolver23

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.registry.successorDemand
import semantics.RuntimeSupport
import semantics.SelectionCompletion
import semantics.coroutineResolve

/**
 * Resolves [selections] through structured coroutines with selective resolver applications. Whether
 * the results contain only the necessary OER nodes has not been proved.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    require(world.selectiveResolvers) {
        "Resolver23 requires selective resolvers"
    }
    val runtimeSupport =
        RuntimeSupport.cycleChecking { completedSelections ->
            SelectionCompletion(
                selections = completedSelections.successorDemand(),
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
