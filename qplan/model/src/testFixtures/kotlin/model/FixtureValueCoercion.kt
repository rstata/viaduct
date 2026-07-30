package model

internal fun coerceSimpleValue(
    type: Schema.SimpleType,
    value: Any,
): Value.Simple =
    when (type) {
        Schema.IntType ->
            requireType<Int>(value, type).let(Value.Int::of)
        Schema.FloatType ->
            requireType<Double>(value, type).let(Value.Float::of)
        Schema.StringType ->
            requireType<String>(value, type).let(Value.String::of)
        Schema.BooleanType ->
            requireType<Boolean>(value, type).let(Value.Boolean::of)
        Schema.IDType ->
            requireType<String>(value, type).let(Value.ID::of)
        is Schema.EnumType ->
            Value.Enum.of(type, requireType<String>(value, type))
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
