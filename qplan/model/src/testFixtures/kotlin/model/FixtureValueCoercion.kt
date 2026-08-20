package model

internal fun coerceSimpleValue(
    type: Schema.SimpleTypeDef,
    value: Any,
): EngineOutputData =
    when (type) {
        is Schema.Scalar ->
            when (type.name) {
                "Int" -> requireType<Int>(value, type)
                "Float" ->
                    requireType<Double>(value, type).also {
                        require(it.isFinite()) { "GraphQL Float values must be finite" }
                    }
                "String" -> requireType<String>(value, type)
                "Boolean" -> requireType<Boolean>(value, type)
                "ID" -> requireType<String>(value, type)
                else -> error("Unsupported scalar: ${type.name}")
            }
        is Schema.Enum ->
            requireType<String>(value, type).also {
                require(type.value(it) != null) { "$it is not a value of ${type.name}" }
            }
    }

private inline fun <reified T : Any> requireType(
    value: Any,
    type: Schema.TypeDef,
): T {
    require(value is T) {
        "Expected ${T::class.simpleName} for ${type.name}"
    }
    return value
}
