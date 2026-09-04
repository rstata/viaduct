package semantics.resolver26

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.SelectionForest
import model.schemaType
import semantics.shared.OperationContext

/**
 * Resolves selective demand once per object-local symbolic key.
 *
 * Keys coalesce when their fields and argument expressions are equal. Variables in those
 * expressions identify their owning resolver occurrences, so equal uses of one variable instance
 * coalesce while variables owned by different resolver occurrences remain distinct.
 */
context(operation: OperationContext)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(
        selections = selections,
        coroutineContext = resolver26CoroutineContext(),
    )

/** Includes validation instrumentation. */
context(operation: OperationContext)
internal fun resolveObserved(
    selections: SelectionForest,
    applicationObserver: Resolver26ApplicationObserver,
): ObjectEngineResult =
    resolve(
        selections = selections,
        coroutineContext = resolver26CoroutineContext(),
        applicationObserver = applicationObserver,
    )

context(operation: OperationContext)
internal fun resolve(
    selections: SelectionForest,
    coroutineContext: CoroutineContext,
    applicationObserver: Resolver26ApplicationObserver = {},
): ObjectEngineResult {
    require(operation.selectiveResolvers) {
        "Resolver26 requires selective resolvers"
    }
    val source = operation.resolverRegistry.createRootQueryInput()
    val result: ObjectEngineResult =
        ObjectEngineResult.of(
            type = source.schemaType,
            mutable = true,
        )
    return runBlocking(coroutineContext) {
        withTimeout(15_000) {
            coroutineScope {
                val resolver26Operation =
                    Resolver26OperationContext(
                        base = operation,
                        requestScope = this,
                        resolverObserver =
                            operation.resolverObserver.withResolver26Applications(
                                applicationObserver,
                            ),
                    )
                val orchestration =
                    ObjectOrchestrationTask(
                        operation = resolver26Operation,
                        occurrence =
                            OEROccurrenceContext(
                                root = result,
                                path = emptyList(),
                                target = result,
                            ),
                        source = source,
                        initialDemand = selections,
                    )
                orchestration.prepare()
                orchestration.launch()
            }
            result
        }
    }
}
