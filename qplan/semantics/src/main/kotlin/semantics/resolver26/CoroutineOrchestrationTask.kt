package semantics.resolver26

import java.util.concurrent.atomic.AtomicBoolean
import model.ObjectSelectionForest
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import semantics.shared.SharedOrchestrationTask
import viaduct.engine.api.EngineObjectData

/**
 * Prepared object task with one-shot dispatch and synchronous field installation/freezing.
 * Supplies its concretely typed operation through [operation], separately from the task lifecycle.
 */
internal abstract class CoroutineOrchestrationTask<O : SharedOperationContext<*>>(
    final override val operation: O,
    final override val occurrence: OEROccurrence,
    final override val source: EngineObjectData.Sync,
) : SharedOrchestrationTask<O> {
    private val launched = AtomicBoolean(false)

    abstract override val closedDemand: ObjectSelectionForest

    internal abstract val hasActiveWork: Boolean

    /** Claims dispatch before validation or entering a request-root coroutine. */
    internal fun checkDispatch() {
        if (!launched.compareAndSet(false, true)) {
            throw duplicateDispatchException()
        }
        validateDispatch()
    }

    protected open fun duplicateDispatchException(): RuntimeException =
        IllegalArgumentException(
            "Orchestration task at ${occurrence.path} was dispatched twice",
        )

    /** Installs field tasks before sealing this object's field set. */
    internal fun run() {
        installFieldTasks()
        occurrence.target.freeze()
    }

    protected open fun validateDispatch() {}

    protected abstract fun installFieldTasks()
}
