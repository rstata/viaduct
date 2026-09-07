package semantics.shared

import model.InclusionCondition
import model.VariableBinding

/** Awaits the operation-local variable bindings needed to decide this condition. */
context(operation: OperationContext)
internal suspend fun InclusionCondition.fetchIncluded(): Boolean =
    include { variable ->
        when (
            val binding =
                operation.variableBindingsState.fetchBinding(
                    requireNotNull(variable.instanceId),
                )
        ) {
            VariableBinding.Error -> error("Inclusion-condition variable failed")
            is VariableBinding.Input ->
                binding.value as? Boolean
                    ?: error("Inclusion-condition variable must contain a Boolean")
        }
    }

/** Evaluates this condition from bindings that must already be complete. */
context(operation: OperationContext)
internal fun InclusionCondition.isIncluded(): Boolean =
    includeWith { variable ->
        when (
            val binding =
                operation.variableBindingsState.getBinding(
                    requireNotNull(variable.instanceId),
                )
        ) {
            VariableBinding.Error -> error("Inclusion-condition variable failed")
            is VariableBinding.Input ->
                binding.value as? Boolean
                    ?: error("Inclusion-condition variable must contain a Boolean")
        }
    }
