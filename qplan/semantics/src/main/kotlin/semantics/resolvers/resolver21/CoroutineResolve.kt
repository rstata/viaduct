package semantics.resolvers.resolver21

import kotlinx.coroutines.coroutineScope
import model.ObjectEngineResult
import model.SelectionForest
import model.schemaType
import semantics.shared.CycleCheckState
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

/** Resolves one operation through request-root tasks and exact value promises. */
internal class CoroutineResolve(
    private val operation: SharedOperationContext<*>,
    private val complete: (SelectionForest) -> SelectionForest,
    private val supportsParentFields: Boolean = false,
    private val cycleChecker: CycleCheckState = CycleCheckState.create(),
) {
    suspend fun resolve(source: EngineObjectData.Sync, selections: SelectionForest): ObjectEngineResult =
        coroutineScope {
            context(CoroutineOperationContext(operation, this, complete, supportsParentFields, cycleChecker)) {
                startResolve(source, selections)
            }
        }
}

/** Prepares and dispatches a fresh Query root; its fields remain owned by the request scope. */
context(operation: CoroutineOperationContext)
internal fun startResolve(source: EngineObjectData.Sync, selections: SelectionForest): ObjectEngineResult {
    val result = ObjectEngineResult.of(source.schemaType, mutable = true)
    operation.dispatcher.dispatchOrchestrator(
        CoroutineOrchestrationTask.create(
            operation, OEROccurrenceContext(result, emptyList(), result), source, selections,
        ),
    )
    return result
}
