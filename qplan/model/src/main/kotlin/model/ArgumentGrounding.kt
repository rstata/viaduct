package model

/**
 * Grounds this argument tuple under [world] and [expectedType].
 *
 * @throws IllegalStateException when a stamped variable is unbound or a template is unstamped
 */
context(world: Assumptions)
internal fun Arguments.instantiateBindings(
    expectedType: Schema.FieldArguments,
): Arguments.Ground {
    if (this == Arguments.Error) return Arguments.Error
    return groundedArguments(expectedType) { value, typeExpr ->
        value.instantiateBindings(typeExpr)
    }
}

/** Grounds this argument tuple under [expectedType], suspending for incomplete stamped variables. */
context(world: Assumptions)
suspend fun Arguments.fetchBindings(
    expectedType: Schema.FieldArguments,
): Arguments.Ground {
    if (this == Arguments.Error) return Arguments.Error
    return groundedArguments(expectedType) { value, typeExpr ->
        value.fetchBindings(typeExpr)
    }
}

private inline fun Arguments.groundedArguments(
    expectedType: Schema.FieldArguments,
    ground: (ArgumentExpression?, TypeExpr<Schema.InputTypeDef>) -> VariableBinding,
): Arguments.Ground {
    val fields = linkedMapOf<String, EngineInputData?>()
    fieldExpressions().forEach { (name, value) ->
        val typeExpr = expectedType.requireField(name).type
        when (val binding = ground(value, typeExpr)) {
            VariableBinding.Error -> return Arguments.Error
            is VariableBinding.Input -> fields[name] = binding.value
        }
    }
    return argumentsOfGround(fields)
}

context(world: Assumptions)
private fun ArgumentExpression?.instantiateBindings(
    expectedType: TypeExpr<Schema.InputTypeDef>,
): VariableBinding =
    when (this) {
        null -> VariableBinding.of(null)
        ArgumentResolutionError -> VariableBinding.Error
        is Arguments.Variable ->
            if (isStamped) {
                world.getBinding(this).coerceTo(expectedType)
            } else {
                error("Variable template $this must be stamped before it can be instantiated")
            }
        is List<*> -> {
            require(expectedType is TypeExpr.List) {
                "Argument list expression does not match $expectedType"
            }
            val grounded = mutableListOf<EngineInputData?>()
            forEach { value ->
                when (val binding = value.instantiateBindings(expectedType.elementType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded += binding.value
                }
            }
            VariableBinding.of(grounded.toList())
        }
        is Map<*, *> -> {
            val expectedObjectType = (expectedType as? TypeExpr.Named)?.baseType
            require(expectedObjectType is Schema.Input) {
                "Argument input-object expression does not match $expectedType"
            }
            val grounded = linkedMapOf<String, EngineInputData?>()
            toStringKeyedArgumentMap().forEach { (name, value) ->
                val fieldType = expectedObjectType.requireField(name).type
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
    expectedType: TypeExpr<Schema.InputTypeDef>,
): VariableBinding {
    return when (this) {
        null -> VariableBinding.of(null)
        ArgumentResolutionError -> VariableBinding.Error
        is Arguments.Variable ->
            if (isStamped) {
                world.fetchBinding(this).coerceTo(expectedType)
            } else {
                error("Variable template $this must be stamped before it can be instantiated")
            }
        is List<*> -> {
            require(expectedType is TypeExpr.List) {
                "Argument list expression does not match $expectedType"
            }
            val grounded = mutableListOf<EngineInputData?>()
            for (value in this) {
                when (val binding = value.fetchBindings(expectedType.elementType)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded += binding.value
                }
            }
            VariableBinding.of(grounded.toList())
        }
        is Map<*, *> -> {
            val expectedObjectType = (expectedType as? TypeExpr.Named)?.baseType
            require(expectedObjectType is Schema.Input) {
                "Argument input-object expression does not match $expectedType"
            }
            val grounded = linkedMapOf<String, EngineInputData?>()
            for ((name, value) in toStringKeyedArgumentMap()) {
                val fieldType = expectedObjectType.requireField(name).type
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
    expectedType: TypeExpr<Schema.InputTypeDef>,
): VariableBinding =
    when (this) {
        VariableBinding.Error -> this
        is VariableBinding.Input ->
            VariableBinding.of(toEngineInputData(expectedType, value))
    }
