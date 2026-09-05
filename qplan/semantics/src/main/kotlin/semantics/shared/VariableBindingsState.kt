package semantics.shared

import java.util.concurrent.ConcurrentHashMap
import model.EngineInputData
import model.Promise
import model.VariableBinding
import model.VariableInstanceId

/** The monotonic variable bindings belonging to one resolution operation. */
class VariableBindingsState {
    private val bindings =
        ConcurrentHashMap<VariableInstanceId, Promise<VariableBinding>>()

    /** Whether [variableId] has a completed binding, including one whose value is null. */
    fun isBound(variableId: VariableInstanceId): Boolean =
        bindings[variableId]?.isCompleted ?: false

    /** Declares one incomplete binding. */
    fun declareBinding(variableId: VariableInstanceId) {
        write(variableId, Promise.ofDeferred())
    }

    /** Installs one already-completed binding. */
    fun bindVariable(
        variableId: VariableInstanceId,
        binding: VariableBinding,
    ) {
        write(variableId, Promise.of(binding))
    }

    fun bindVariable(
        variableId: VariableInstanceId,
        value: EngineInputData?,
    ) = bindVariable(variableId, VariableBinding.of(value))

    /** Completes one previously declared binding. */
    fun completeBinding(
        variableId: VariableInstanceId,
        binding: VariableBinding,
    ) {
        bindingPromise(variableId).complete(binding)
    }

    fun completeBinding(
        variableId: VariableInstanceId,
        value: EngineInputData?,
    ) = completeBinding(variableId, VariableBinding.of(value))

    /** Reads one completed binding without suspending. */
    fun getBinding(variableId: VariableInstanceId): VariableBinding =
        bindingPromise(variableId).get()

    /** Awaits one declared binding. */
    suspend fun fetchBinding(variableId: VariableInstanceId): VariableBinding =
        bindingPromise(variableId).await()

    private fun write(
        variableId: VariableInstanceId,
        binding: Promise<VariableBinding>,
    ) {
        check(bindings.putIfAbsent(variableId, binding) == null) {
            "$variableId already written"
        }
    }

    private fun bindingPromise(
        variableId: VariableInstanceId,
    ): Promise<VariableBinding> =
        checkNotNull(bindings[variableId]) { "$variableId not found" }
}
