package semantics.resolver26

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.ObjectEngineResult
import model.SelectionForest
import model.schemaType
import semantics.ResolverSupport

/**
 * Resolves selective demand once per ordinary or occurrence-stamped resolver instance.
 *
 * Pre-grounded selections coalesce by ordinary ground key. Every variable-bearing selection in a
 * resolver object fragment retains its occurrence lineage and therefore resolves
 * independently.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(
        selections = selections,
        coroutineContext = resolver26CoroutineContext(),
    )

/** Includes validation instrumentation. */
context(world: Assumptions)
internal fun resolveObserved(
    selections: SelectionForest,
    applicationObserver: Resolver26ApplicationObserver,
): ObjectEngineResult =
    resolve(
        selections = selections,
        coroutineContext = resolver26CoroutineContext(),
        applicationObserver = applicationObserver,
    )

context(world: Assumptions)
internal fun resolve(
    selections: SelectionForest,
    coroutineContext: CoroutineContext,
    applicationObserver: Resolver26ApplicationObserver = {},
): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver26 requires selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val result: ObjectEngineResult =
        ObjectEngineResult.of(
            type = source.schemaType,
            mutable = true,
        )
    return runBlocking(coroutineContext) {
        withTimeout(15_000) {
            coroutineScope {
                val support =
                    Resolver26Support(
                        requestScope = this,
                        applicationObserver = applicationObserver,
                        resolverSupport = ResolverSupport.cycleChecking(),
                )
                ObjectOrchestrationTask(
                    world = world,
                    support = support,
                    path = emptyList(),
                    source = source,
                    target = result,
                    initialDemand = selections,
                ).run()
            }
            result
        }
    }
}
