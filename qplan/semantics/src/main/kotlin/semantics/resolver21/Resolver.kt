package semantics.resolver21

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.SelectionForest
import semantics.RuntimeSupport
import semantics.coroutineResolve

/**
 * Resolves [selections] with structured coroutines when user-declared resolver object fragments are
 * empty, except for generated `T_V_A_Bridge.node` fragments that select passive sibling `id`.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): EngineResult.Object {
    require(!world.selectiveResolvers) {
        "Resolver21 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.resolveRootQuery()
    val runtimeSupport =
        RuntimeSupport.cycleChecking { completedSelections -> completedSelections }
    return runBlocking {
        withTimeout(90_000) {
            context(runtimeSupport) {
                source.coroutineResolve(selections)
            }
        }
    }
}
