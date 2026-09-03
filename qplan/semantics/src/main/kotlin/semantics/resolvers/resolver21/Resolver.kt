package semantics.resolvers.resolver21

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.SelectionForest
import semantics.shared.OperationContext

/**
 * Resolves [selections] with structured coroutines when user-declared resolver object fragments are
 * empty, except for generated `T_V_A_Bridge.node` fragments that select passive sibling `id`.
 */
context(operation: OperationContext)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(!operation.selectiveResolvers) {
        "Resolver21 requires non-selective resolvers"
    }
    val source = operation.resolverRegistry.createRootQueryInput()
    val resolver =
        CoroutineResolve(
            operation = operation,
            complete = { completedSelections -> completedSelections },
        )
    return runBlocking {
        withTimeout(90_000) {
            resolver.resolve(source, selections)
        }
    }
}
