package semantics.resolver21

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import semantics.ResolverSupport
import semantics.coroutineResolve

/**
 * Resolves [selections] with structured coroutines when user-declared resolver object fragments are
 * empty, except for generated `T_V_A_Bridge.node` fragments that select passive sibling `id`.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver21 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val resolverSupport =
        ResolverSupport.cycleChecking { completedSelections -> completedSelections }
    return runBlocking {
        withTimeout(90_000) {
            context(resolverSupport) {
                source.coroutineResolve(selections)
            }
        }
    }
}
