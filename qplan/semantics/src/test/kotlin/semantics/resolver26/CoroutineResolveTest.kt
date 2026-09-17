package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.SelectionForest
import model.schemaType
import semantics.contract.CoroutineResolverContract
import semantics.shared.CycleCheckState
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedOperationContext

class CoroutineResolveTest : CoroutineResolverContract {
    override fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult {
        val resolverOperation = OperationContext(
            operation, requestScope, operation.resolverObserver.withResolver26Applications {}, cycleChecker,
        )
        val source = operation.resolverRegistry.createRootQueryInput()
        val root = ObjectEngineResult.of(source.schemaType, mutable = true)
        resolverOperation.dispatcher.dispatchOrchestrator(
            OrchestrationTask.create(resolverOperation, OEROccurrenceContext(root, emptyList(), root), source, selections),
        )
        return root
    }
}
