package model

/** Int, finite Double, Boolean, String, [EngineIDData], or [EngineEnumValueData]. */
typealias EngineSimpleData = Any

/** A recursively validated list of nullable engine input values. */
typealias EngineInputListData = List<EngineInputData?>

/** A recursively validated input-object field map. */
typealias EngineInputObjectData = Map<String, EngineInputData?>

/** [EngineSimpleData], [EngineInputListData], or [EngineInputObjectData]. */
typealias EngineInputData = Any

/** A GraphQL ID, kept distinct from a GraphQL String in engine input data. */
data class EngineIDData(
    val id: String,
)

/** A GraphQL enum member and its canonical enum definition. */
data class EngineEnumValueData(
    val value: String,
    val type: Schema.EnumType,
) {
    init {
        require(value in type.values) { "$value is not a value of ${type.typeName}" }
    }
}

fun SimpleEngineResult.toEngineSimpleData(): EngineSimpleData =
    when (this) {
        is IntEngineResult -> intValue
        is FloatEngineResult -> floatValue
        is StringEngineResult -> stringValue
        is BooleanEngineResult -> booleanValue
        is IDEngineResult -> EngineIDData(idValue)
        is EnumEngineResult -> EngineEnumValueData(enumValue, type)
    }

/** Recursively copies [value] as [EngineInputData] conforming to [expectedType]. */
fun toEngineInputData(
    expectedType: TypeExpr<Schema.InputType>,
    value: EngineInputData?,
): EngineInputData? {
    if (value == null) {
        if (!expectedType.isNullable) throw ClassCastException()
        return null
    }
    if (value == Value.Error) throw ClassCastException()

    return when (expectedType) {
        is TypeExpr.Named -> toEngineNamedInputData(expectedType.baseType, value)
        is TypeExpr.List -> {
            val elements = value.cast<EngineInputListData>()
            toEngineInputListData(expectedType, elements)
        }
    }
}

/** Converts [value] to canonical [EngineSimpleData] conforming to [expectedType]. */
fun toEngineSimpleData(
    expectedType: TypeExpr.Named<Schema.SimpleType>,
    value: EngineSimpleData?,
): EngineSimpleData? {
    if (value == null) {
        if (!expectedType.isNullable) throw ClassCastException()
        return null
    }
    return toEngineSimpleData(expectedType.baseType, value)
}

/** Recursively copies [value] as canonical list data conforming to [expectedType]. */
fun toEngineInputListData(
    expectedType: TypeExpr.List<Schema.InputType>,
    value: EngineInputListData,
): EngineInputListData =
    value.map { element ->
        toEngineInputData(expectedType.elementType, element)
    }

/** Recursively copies [value] as canonical input-object data conforming to [expectedType]. */
fun toEngineInputObjectData(
    expectedType: Schema.InputObjectType,
    value: EngineInputObjectData,
): EngineInputObjectData = toEngineInputFields(expectedType, value)

private fun toEngineNamedInputData(
    expectedType: Schema.InputType,
    value: EngineInputData,
): EngineInputData =
    when (expectedType) {
        is Schema.SimpleType -> toEngineSimpleData(expectedType, value)
        is Schema.InputObjectType -> {
            val fields = value as? Map<*, *> ?: throw ClassCastException()
            toEngineInputObjectData(expectedType, fields.toStringKeyedMap())
        }
    }

private fun toEngineSimpleData(
    expectedType: Schema.SimpleType,
    value: EngineSimpleData,
): EngineSimpleData =
    when (expectedType) {
        Schema.IntType -> value.cast<Int>()
        Schema.FloatType ->
            value.cast<Double>().also { if (!it.isFinite()) throw ClassCastException() }
        Schema.StringType -> value.cast<String>()
        Schema.BooleanType -> value.cast<Boolean>()
        Schema.IDType ->
            when (value) {
                is EngineIDData -> value
                is String -> EngineIDData(value)
                else -> throw ClassCastException()
            }
        is Schema.EnumType ->
            when (value) {
                is EngineEnumValueData -> value.validatedFor(expectedType)
                is String -> EngineEnumValueData(value, expectedType)
                else -> throw ClassCastException()
            }
    }

private inline fun <reified T> Any.cast(): T = this as? T ?: throw ClassCastException()

private fun EngineEnumValueData.validatedFor(
    expectedType: Schema.EnumType,
): EngineEnumValueData {
    if (type != expectedType) throw ClassCastException()
    return this
}

internal fun toEngineInputFields(
    expectedType: Schema.InputObjectLike,
    fields: EngineInputObjectData,
): EngineInputObjectData {
    val supplied =
        fields.mapValues { (name, value) ->
            val field = expectedType.fields[name] ?: throw ClassCastException()
            toEngineInputData(field.typeExpr, value)
        }

    return buildMap {
        expectedType.fields.values.forEach { field ->
            val defaultValue = field.defaultValue
            if (defaultValue is Value.Default.Present) {
                put(field.name, defaultValue.value)
            }
        }
        putAll(supplied)
        if (!keys.containsAll(expectedType.requiredFieldNames())) throw ClassCastException()
    }
}

internal fun Schema.InputObjectLike.requiredFieldNames(): Set<String> =
    fields.values
        .filterTo(linkedSetOf()) { field ->
            !field.typeExpr.isNullable && field.defaultValue == Value.Default.Absent
        }.mapTo(linkedSetOf(), Schema.InputLikeField::name)

private fun Map<*, *>.toStringKeyedMap(): EngineInputObjectData =
    entries.associate { (key, value) ->
        if (key !is String) throw ClassCastException()
        key to value
    }
