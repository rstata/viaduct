package model.invariants

import model.Arguments

import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.EngineInputData
import model.EngineInputListData
import model.EngineInputObjectData
import model.EngineIDResult
import model.ErrorEngineResult
import model.CoercedDefaultValue
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.TypeExpr
import model.canContainPure
import model.conformsToArgumentDefinition
import viaduct.engine.api.EngineObjectData

/**
 * Whether this EOD recursively contains only engine output data.
 *
 * Qplan's factory validates each selection against its canonical schema field before forgetting
 * that field metadata. This relation checks the retained values without reconstructing field
 * identity from response-key strings.
 */
context(world: Assumptions)
internal fun EngineObjectData.Sync.conformsToSchema(): Boolean = this.conformsToOutputData()

/**
 * Whether this input value recursively conforms to [typeExpr].
 *
 * Null conforms exactly at a nullable outer layer.
 */
context(world: Assumptions)
internal fun EngineInputData?.conformsToSchema(
    typeExpr: TypeExpr<Schema.InputTypeDef>,
): Boolean = conformsToInputSchemaType(typeExpr)

/**
 * Whether this output value recursively conforms to [typeExpr].
 *
 * Null conforms exactly at a nullable outer layer and [EngineErrorData] conforms to every output
 * type expression.
 */
context(world: Assumptions)
internal fun EngineOutputData?.conformsToOutputSchema(
    typeExpr: TypeExpr<Schema.OutputTypeDef>,
): Boolean =
    conformsToOutputSchemaType(typeExpr) &&
        (this !is EngineObjectData.Sync || conformsToOutputData())

/** Whether this argument tuple recursively conforms to [expectedType]. */
context(world: Assumptions)
internal fun Arguments.Resolved.conformsToSchema(
    expectedField: Schema.Field,
): Boolean = conformsToArgumentDefinition(expectedField)

/** Whether this key's arguments recursively conform to its output field. */
context(world: Assumptions)
internal fun ObjectEngineResult.Key.conformsToSchema(): Boolean {
    val keyArguments = arguments
    return keyArguments.conformsToArgumentDefinition(field)
}

/**
 * Whether this engine result recursively conforms to the schema definitions carried by its
 * coordinates.
 *
 * This relation is universally true of engine results constructed by their model factories.
 */
context(world: Assumptions)
internal fun EngineResult.conformsToSchema(): Boolean =
    when (this) {
        ErrorEngineResult -> true
        is ObjectEngineResult ->
            keys.all { key ->
                val cell = getCell(key)
                val value = getCell(key).getValue().get()
                key.field.containingDef == type &&
                    key.conformsToSchema() &&
                    value.conformsToResultSchemaType(key.field.type) &&
                    (value?.conformsToSchema() ?: true) &&
                    cell.getAccessResult().get().conformsToAccessResult()
            }
        is ListEngineResult ->
            all { cell ->
                val value = cell.getValue().get()
                value.conformsToResultSchemaType(typeExpr) &&
                    (value?.conformsToSchema() ?: true) &&
                    cell.getAccessResult().get().conformsToAccessResult()
            }
        is Schema.EnumValue ->
            containingDef.value(name) == this
        is Double -> isFinite()
        is Int,
        is Boolean,
        is String,
        is EngineIDResult,
        -> true
        else -> false
    }

private fun EngineInputData.conformsToInputObjectType(
    expectedType: Schema.Input,
): Boolean {
    val fieldValues = asEngineInputObjectDataOrNull() ?: return false
    if (
        expectedType.fields.any { field ->
            !field.type.isNullable &&
                field.defaultValue == CoercedDefaultValue.Absent &&
                field.name !in fieldValues
        }
    ) {
        return false
    }
    return fieldValues.all { (fieldName, value) ->
        val field = expectedType.field(fieldName) ?: return@all false
        value.conformsToInputSchemaType(field.type)
    }
}

internal fun EngineInputData?.conformsToInputSchemaType(
    typeExpr: TypeExpr<Schema.InputTypeDef>,
): Boolean {
    if (this == null) return typeExpr.isNullable

    val elementType = typeExpr.unwrapList()
    if (elementType != null) {
        return asEngineInputListDataOrNull()
            ?.all { value -> value.conformsToInputSchemaType(elementType) }
            ?: false
    }

    return when (val expectedType = typeExpr.baseTypeDef) {
        is Schema.Scalar ->
            when (expectedType.name) {
                "Int" -> this is Int
                "Float" -> this is Double && isFinite()
                "String" -> this is String
                "Boolean" -> this is Boolean
                "ID" -> this is String
                else -> false
            }
        is Schema.Enum ->
            this is String &&
                expectedType.value(this) != null
        is Schema.Input ->
            conformsToInputObjectType(expectedType)
    }
}

private fun EngineInputData.asEngineInputListDataOrNull(): EngineInputListData? {
    val values = this as? List<*> ?: return null
    @Suppress("UNCHECKED_CAST")
    return values as EngineInputListData
}

private fun EngineInputData.asEngineInputObjectDataOrNull(): EngineInputObjectData? {
    val fields = this as? Map<*, *> ?: return null
    if (fields.keys.any { key -> key !is String }) return null
    @Suppress("UNCHECKED_CAST")
    return fields as EngineInputObjectData
}

fun EngineOutputData?.conformsToOutputSchemaType(
    typeExpr: TypeExpr<Schema.OutputTypeDef>,
): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        EngineErrorData -> true
        is List<*> ->
            typeExpr.unwrapList()
                ?.let { elementType ->
                    all { value -> value.conformsToOutputSchemaType(elementType) }
                } == true
        is EngineObjectData.Sync ->
            !typeExpr.isList &&
                (typeExpr.baseTypeDef as? Schema.CompositeTypeDef)
                    ?.possibleObjectTypes
                    ?.any { possibleType -> possibleType.name == type.name } == true
        is Int -> typeExpr.hasScalarType("Int")
        is Double ->
            isFinite() &&
                typeExpr.hasScalarType("Float")
        is String ->
            !typeExpr.isList &&
                when (val expected = typeExpr.baseTypeDef) {
                    is Schema.Scalar -> expected.name == "String" || expected.name == "ID"
                    is Schema.Enum -> expected.value(this) != null
                    else -> false
                }
        is Boolean -> typeExpr.hasScalarType("Boolean")
        else -> false
    }

private fun EngineOutputData?.conformsToOutputData(): Boolean =
    when (this) {
        null,
        EngineErrorData,
        is Int,
        is Boolean,
        is String,
        -> true
        is Double -> isFinite()
        is List<*> -> all { value -> value.conformsToOutputData() }
        is EngineObjectData.Sync ->
            getSelections().all { selection -> get(selection).conformsToOutputData() }
        else -> false
    }

internal fun EngineResult?.conformsToResultSchemaType(
    typeExpr: TypeExpr<Schema.OutputTypeDef>,
): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        ErrorEngineResult -> true
        is ObjectEngineResult ->
            if (!typeExpr.isList) {
                val declaredType = typeExpr.baseTypeDef
                declaredType is Schema.CompositeTypeDef && type in declaredType.possibleObjectTypes
            } else {
                false
            }
        is ListEngineResult ->
            typeExpr.unwrapList()?.canContainPure(this.typeExpr) == true
        is Int -> typeExpr.hasScalarType("Int")
        is Double ->
            isFinite() &&
                typeExpr.hasScalarType("Float")
        is String -> typeExpr.hasScalarType("String")
        is Boolean -> typeExpr.hasScalarType("Boolean")
        is EngineIDResult -> typeExpr.hasScalarType("ID")
        is Schema.EnumValue ->
            !typeExpr.isList &&
                typeExpr.baseTypeDef == containingDef &&
                containingDef.value(name) == this
        else -> false
    }

private fun TypeExpr<*>.hasScalarType(expectedName: String): Boolean =
    !isList &&
        (baseTypeDef as? Schema.Scalar)?.name == expectedName

internal fun EngineResult.conformsToAccessResult(): Boolean =
    this is Boolean || this == ErrorEngineResult
