package semantics.shared

import model.Arguments
import model.groundWithBindings
import viaduct.graphql.schema.ViaductSchema

/** Grounds this argument tuple using the bindings currently completed in [operation]. */
internal fun Arguments.instantiateBindings(
    operation: SharedOperationContext<*>,
    expectedField: ViaductSchema.Field,
): Arguments.Ground =
    groundWithBindings(expectedField) { variable ->
        operation.variableBindings.getBinding(requireNotNull(variable.instanceId))
    }
