package semantics.shared

import model.Arguments
import model.fetchGroundWithBindings
import model.groundWithBindings
import viaduct.graphql.schema.ViaductSchema

/** Grounds this argument tuple using the bindings currently completed in [operation]. */
context(operation: OperationContext)
internal fun Arguments.instantiateBindings(
    expectedField: ViaductSchema.Field,
): Arguments.Ground =
    groundWithBindings(expectedField) { variable ->
        operation.variableBindingsState.getBinding(requireNotNull(variable.instanceId))
    }

/** Grounds this argument tuple, suspending for incomplete bindings in [operation]. */
context(operation: OperationContext)
suspend fun Arguments.fetchBindings(
    expectedField: ViaductSchema.Field,
): Arguments.Ground =
    fetchGroundWithBindings(expectedField) { variable ->
        operation.variableBindingsState.fetchBinding(requireNotNull(variable.instanceId))
    }
