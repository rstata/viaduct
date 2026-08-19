package model

import viaduct.engine.api.EngineObjectData

/**
 * Int, finite Double, Boolean, or String. String represents GraphQL String, ID, and enum values;
 * the expected schema type disambiguates them.
 */
typealias EngineSimpleData = Any

/** A recursively validated list of nullable engine input values. */
typealias EngineInputListData = List<EngineInputData?>

/** A recursively validated input-object field map. */
typealias EngineInputObjectData = Map<String, EngineInputData?>

/** [EngineSimpleData], [EngineInputListData], or [EngineInputObjectData]. */
typealias EngineInputData = Any

/**
 * An optional, fully coerced semantic schema default.
 *
 * [Absent] means that no default is declared. [Present.value] may be null, denoting an explicit
 * GraphQL null; absence and explicit null are distinct. When attached to a field or argument,
 * [Present] is valid for its declaring [TypeExpr]. Default values use structural equality.
 */
sealed interface CoercedDefaultValue {
    data object Absent : CoercedDefaultValue

    sealed interface Present : CoercedDefaultValue {
        val value: EngineInputData?
    }

    companion object {
        fun of(value: EngineInputData?): Present = PresentCoercedDefaultValueImpl(value)
    }
}

private data class PresentCoercedDefaultValueImpl(
    override val value: EngineInputData?,
) : CoercedDefaultValue.Present

/**
 * Int, finite Double, Boolean, String, [EngineObjectData.Sync], or [EngineOutputListData].
 *
 * String represents GraphQL String, ID, and enum values; the expected schema type disambiguates
 * them. [EngineErrorData] is additionally admitted to the broad output domain.
 */
typealias EngineOutputData = Any

/** A recursively validated list of nullable engine output values. */
typealias EngineOutputListData = List<EngineOutputData?>

/**
 * The collapsed resolver-output error sentinel.
 *
 * This belongs only to the broad [EngineOutputData] domain. It is distinct from
 * [ErrorEngineResult] and argument-resolution failure.
 */
data object EngineErrorData

/** Converts a simple engine result to production-compatible engine input data. */
fun EngineResult.toEngineSimpleData(expectedType: Schema.SimpleTypeDef): EngineSimpleData =
    when (expectedType) {
        Schema.IntType -> cast<Int>()
        Schema.FloatType -> cast<Double>().also { if (!it.isFinite()) throw ClassCastException() }
        Schema.StringType -> cast<String>()
        Schema.BooleanType -> cast<Boolean>()
        Schema.IDType -> cast<EngineIDResult>().value
        is Schema.Enum -> {
            val value = cast<Schema.EnumValue>()
            if (value.containingDef != expectedType) throw ClassCastException()
            value.name
        }
    }

/** Recursively copies [value] as [EngineInputData] conforming to [expectedType]. */
internal fun toEngineInputData(
    expectedType: TypeExpr<Schema.InputTypeDef>,
    value: EngineInputData?,
): EngineInputData? {
    if (value == null) {
        if (!expectedType.isNullable) throw ClassCastException()
        return null
    }

    return when (expectedType) {
        is TypeExpr.Named -> toEngineNamedInputData(expectedType.baseType, value)
        is TypeExpr.List -> {
            val elements = value.cast<EngineInputListData>()
            toEngineInputListData(expectedType, elements)
        }
    }
}

/** Converts [value] to canonical [EngineSimpleData] conforming to [expectedType]. */
internal fun toEngineSimpleData(
    expectedType: TypeExpr.Named<Schema.SimpleTypeDef>,
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
    expectedType: TypeExpr.List<Schema.InputTypeDef>,
    value: EngineInputListData,
): EngineInputListData =
    value.map { element ->
        toEngineInputData(expectedType.elementType, element)
    }

/** Recursively copies [value] as canonical input-object data conforming to [expectedType]. */
internal fun toEngineInputObjectData(
    expectedType: Schema.Input,
    value: EngineInputObjectData,
): EngineInputObjectData = toEngineInputFields(expectedType, value)

private fun toEngineNamedInputData(
    expectedType: Schema.InputTypeDef,
    value: EngineInputData,
): EngineInputData =
    when (expectedType) {
        is Schema.SimpleTypeDef -> toEngineSimpleData(expectedType, value)
        is Schema.Input -> {
            val fields = value as? Map<*, *> ?: throw ClassCastException()
            toEngineInputObjectData(expectedType, fields.toStringKeyedMap())
        }
    }

private fun toEngineSimpleData(
    expectedType: Schema.SimpleTypeDef,
    value: EngineSimpleData,
): EngineSimpleData =
    when (expectedType) {
        Schema.IntType -> value.cast<Int>()
        Schema.FloatType ->
            value.cast<Double>().also { if (!it.isFinite()) throw ClassCastException() }
        Schema.StringType -> value.cast<String>()
        Schema.BooleanType -> value.cast<Boolean>()
        Schema.IDType -> value.cast<String>()
        is Schema.Enum ->
            value.cast<String>().also {
                if (expectedType.value(it) == null) throw ClassCastException()
            }
    }

private inline fun <reified T> Any.cast(): T = this as? T ?: throw ClassCastException()

/** Converts simple resolver output to the result representation selected by [expectedType]. */
fun EngineOutputData.toEngineResult(expectedType: Schema.SimpleTypeDef): EngineResult =
    when (expectedType) {
        Schema.IntType -> cast<Int>()
        Schema.FloatType -> cast<Double>().also { if (!it.isFinite()) throw ClassCastException() }
        Schema.StringType -> cast<String>()
        Schema.BooleanType -> cast<Boolean>()
        Schema.IDType -> EngineIDResult.of(cast())
        is Schema.Enum -> expectedType.requireValue(cast())
    }

/** Converts a simple engine result to production-compatible resolver output. */
fun EngineResult.toEngineOutputData(expectedType: Schema.SimpleTypeDef): EngineOutputData =
    when (expectedType) {
        Schema.IntType -> cast<Int>()
        Schema.FloatType -> cast<Double>().also { if (!it.isFinite()) throw ClassCastException() }
        Schema.StringType -> cast<String>()
        Schema.BooleanType -> cast<Boolean>()
        Schema.IDType -> cast<EngineIDResult>().value
        is Schema.Enum -> {
            val value = cast<Schema.EnumValue>()
            if (value.containingDef != expectedType) throw ClassCastException()
            value.name
        }
    }

private fun toEngineInputFields(
    expectedType: Schema.Input,
    fields: EngineInputObjectData,
): EngineInputObjectData {
    val supplied =
        fields.mapValues { (name, value) ->
            val field = expectedType.field(name) ?: throw ClassCastException()
            toEngineInputData(field.type, value)
        }

    return buildMap {
        expectedType.fields.forEach { field ->
            val defaultValue = field.defaultValue
            if (defaultValue is CoercedDefaultValue.Present) {
                put(field.name, defaultValue.value)
            }
        }
        putAll(supplied)
        if (!keys.containsAll(expectedType.requiredFieldNames())) throw ClassCastException()
    }
}

internal fun Schema.Input.requiredFieldNames(): Set<String> =
    fields
        .filterTo(linkedSetOf()) { field ->
            !field.type.isNullable && field.defaultValue == CoercedDefaultValue.Absent
        }.mapTo(linkedSetOf(), Schema.InputLikeField::name)

internal fun Schema.Field.requiredArgNames(): Set<String> =
    args
        .filterTo(linkedSetOf()) { arg ->
            !arg.type.isNullable && arg.defaultValue == CoercedDefaultValue.Absent
        }.mapTo(linkedSetOf(), Schema.InputLikeField::name)

private fun Map<*, *>.toStringKeyedMap(): EngineInputObjectData =
    entries.associate { (key, value) ->
        if (key !is String) throw ClassCastException()
        key to value
    }
