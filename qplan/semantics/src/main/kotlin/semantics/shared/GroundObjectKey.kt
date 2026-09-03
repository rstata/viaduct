package semantics.shared

import model.Arguments
import model.ObjectEngineResult
import model.usedVariables

/** Whether every variable in this key has an occurrence identity and a completed binding. */
context(operation: OperationContext)
fun ObjectEngineResult.ObjectKey.isContextuallyGrounded(): Boolean =
    arguments.usedVariables().all { variable ->
        variable.isInstantiated &&
            operation.variableBindingsState.isBound(requireNotNull(variable.instanceId))
    }

/** Grounds this key's arguments without changing the symbolic key retained by its OER cell. */
context(operation: OperationContext)
fun ObjectEngineResult.ObjectKey.groundedArguments(): Arguments.Ground {
    require(isContextuallyGrounded()) {
        "Object key is not contextually grounded"
    }
    return arguments.instantiateBindings(field)
}

/** Awaits every variable carried by this key before grounding its arguments. */
context(operation: OperationContext)
suspend fun ObjectEngineResult.ObjectKey.fetchGroundedArguments(): Arguments.Ground {
    arguments.usedVariables().forEach { variable ->
        require(variable.isInstantiated) {
            "Variable template $variable must be instantiated before its binding can be fetched"
        }
        operation.variableBindingsState.fetchBinding(requireNotNull(variable.instanceId))
    }
    return groundedArguments()
}
