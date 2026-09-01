package model

import viaduct.graphql.schema.ViaductSchema

/**
 * Grounds this argument tuple under [world] for [expectedField].
 *
 * @throws IllegalStateException when a variable instance is unbound or a template is uninstantiated
 */
context(world: Assumptions)
internal fun Arguments.instantiateBindings(
    expectedField: ViaductSchema.Field,
): Arguments.Ground {
    if (this == Arguments.Error) return Arguments.Error
    return groundedArguments(expectedField) { value, typeExpr ->
        value.instantiateBindings(typeExpr)
    }
}

/** Grounds this argument tuple for [expectedField], suspending for incomplete variable instances. */
context(world: Assumptions)
suspend fun Arguments.fetchBindings(
    expectedField: ViaductSchema.Field,
): Arguments.Ground {
    if (this == Arguments.Error) return Arguments.Error
    return groundedArguments(expectedField) { value, typeExpr ->
        value.fetchBindings(typeExpr)
    }
}

private inline fun Arguments.groundedArguments(
    expectedField: ViaductSchema.Field,
    ground: (ArgumentExpression?, ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>) -> VariableBinding,
): Arguments.Ground {
    val fields = linkedMapOf<String, EngineInputData?>()
    fieldExpressions().forEach { (name, value) ->
        val typeExpr = expectedField.requireArg(name).inputType
        when (val binding = ground(value, typeExpr)) {
            VariableBinding.Error -> return Arguments.Error
            is VariableBinding.Input -> fields[name] = binding.value
        }
    }
    return argumentsOfGround(fields)
}

context(world: Assumptions)
private fun ArgumentExpression?.instantiateBindings(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
): VariableBinding =
    when (this) {
        null -> VariableBinding.of(null)
        ArgumentResolutionError -> VariableBinding.Error
        is Arguments.Variable ->
            if (isInstantiated) {
                world.getBinding(requireNotNull(instanceId)).coerceTo(expectedType)
            } else {
                error("Variable template $this must be instantiated before it can be grounded")
            }
        is List<*> -> {
            val elementType = expectedType.unwrapList()
            require(elementType != null) {
                "Argument list expression does not match $expectedType"
            }
            val grounded = mutableListOf<EngineInputData?>()
            forEach { value ->
                when (val binding = value.instantiateBindings(elementType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded += binding.value
                }
            }
            VariableBinding.of(grounded.toList())
        }
        is Map<*, *> -> {
            val expectedObjectType = expectedType.baseTypeDef.takeUnless { expectedType.isList }
            require(expectedObjectType is ViaductSchema.Input) {
                "Argument input-object expression does not match $expectedType"
            }
            val grounded = linkedMapOf<String, EngineInputData?>()
            toStringKeyedArgumentMap().forEach { (name, value) ->
            val fieldType = expectedObjectType.requireField(name).inputType
                when (val binding = value.instantiateBindings(fieldType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded[name] = binding.value
                }
            }
            VariableBinding.of(grounded.toMap())
        }
        else -> VariableBinding.of(toEngineInputData(expectedType, this))
    }

context(world: Assumptions)
private suspend fun ArgumentExpression?.fetchBindings(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
): VariableBinding {
    return when (this) {
        null -> VariableBinding.of(null)
        ArgumentResolutionError -> VariableBinding.Error
        is Arguments.Variable ->
            if (isInstantiated) {
                world.fetchBinding(requireNotNull(instanceId)).coerceTo(expectedType)
            } else {
                error("Variable template $this must be instantiated before it can be grounded")
            }
        is List<*> -> {
            val elementType = expectedType.unwrapList()
            require(elementType != null) {
                "Argument list expression does not match $expectedType"
            }
            val grounded = mutableListOf<EngineInputData?>()
            for (value in this) {
                when (val binding = value.fetchBindings(elementType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded += binding.value
                }
            }
            VariableBinding.of(grounded.toList())
        }
        is Map<*, *> -> {
            val expectedObjectType = expectedType.baseTypeDef.takeUnless { expectedType.isList }
            require(expectedObjectType is ViaductSchema.Input) {
                "Argument input-object expression does not match $expectedType"
            }
            val grounded = linkedMapOf<String, EngineInputData?>()
            for ((name, value) in toStringKeyedArgumentMap()) {
            val fieldType = expectedObjectType.requireField(name).inputType
                when (val binding = value.fetchBindings(fieldType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded[name] = binding.value
                }
            }
            VariableBinding.of(grounded.toMap())
        }
        else -> VariableBinding.of(toEngineInputData(expectedType, this))
    }
}

private fun VariableBinding.coerceTo(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
): VariableBinding =
    when (this) {
        VariableBinding.Error -> this
        is VariableBinding.Input ->
            VariableBinding.of(toEngineInputData(expectedType, value))
    }
