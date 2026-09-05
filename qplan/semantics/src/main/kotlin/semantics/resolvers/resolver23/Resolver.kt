package semantics.resolvers.resolver23

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver21.CoroutineResolve
import semantics.resolvers.successorDemand
import semantics.shared.OperationContext

/**
 * Resolves [selections] through structured coroutines with selective resolver applications. Whether
 * the results contain only the necessary OER nodes has not been proved.
 */
context(operation: OperationContext)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(operation.selectiveResolvers) {
        "Resolver23 requires selective resolvers"
    }
    val source = operation.resolverRegistry.createRootQueryInput()
    val resolver =
        CoroutineResolve(
            operation = operation,
            supportsParentFields = true,
            complete = { completedSelections ->
                context(operation.world) {
                    completedSelections.successorDemand()
                }
            },
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
