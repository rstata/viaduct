package semantics.resolvers.resolver21

import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CoroutineResolverContract
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext

class CoroutineResolveTest : CoroutineResolverContract {
    override val selectiveResolvers = false

    override fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult = startCoroutineResolution(operation, requestScope, selections, cycleChecker)
}

internal fun startCoroutineResolution(
    operation: SharedOperationContext<*>,
    requestScope: CoroutineScope,
    selections: SelectionForest,
    cycleChecker: CycleCheckState,
    supportsParentFields: Boolean = false,
    complete: (SelectionForest) -> SelectionForest = { it },
): ObjectEngineResult = context(
    CoroutineOperationContext(operation, requestScope, complete, supportsParentFields, cycleChecker),
) {
    startResolve(operation.resolverRegistry.createRootQueryInput(), selections)
}
