package semantics.resolvers.resolver22

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.SelectionForest
import semantics.resolvers.resolver21.CoroutineResolve
import semantics.resolvers.successorBoundaryDemand
import semantics.shared.OperationContext

/**
 * Resolves [selections] through structured coroutines with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(operation: OperationContext)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(!operation.selectiveResolvers) {
        "Resolver22 requires non-selective resolvers"
    }
    val source = operation.resolverRegistry.createRootQueryInput()
    val resolver =
        CoroutineResolve(
            operation = operation,
            supportsParentFields = true,
            complete = { completedSelections ->
                context(operation.world) {
                    completedSelections.successorBoundaryDemand()
                }
            },
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
