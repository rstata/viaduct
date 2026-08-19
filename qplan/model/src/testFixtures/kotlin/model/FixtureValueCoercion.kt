package model

internal fun coerceSimpleValue(
    type: Schema.SimpleType,
    value: Any,
): EngineOutputData =
    when (type) {
        Schema.IntType -> requireType<Int>(value, type)
        Schema.FloatType ->
            requireType<Double>(value, type).also {
                require(it.isFinite()) { "GraphQL Float values must be finite" }
            }
        Schema.StringType -> requireType<String>(value, type)
        Schema.BooleanType -> requireType<Boolean>(value, type)
        Schema.IDType -> requireType<String>(value, type)
        is Schema.EnumType ->
            requireType<String>(value, type).also {
                require(it in type.values) { "$it is not a value of ${type.typeName}" }
            }
    }

private inline fun <reified T : Any> requireType(
    value: Any,
    type: Schema.Type,
): T {
    require(value is T) {
        "Expected ${T::class.simpleName} for ${type.typeName}"
    }
    return value
}
