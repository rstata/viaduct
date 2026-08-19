package model.invariants

import model.Assumptions
import model.EngineResult
import model.EngineInputData
import model.EngineInputListData
import model.EngineInputObjectData
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.canContainPure
import model.conformsToArgumentDefinition

/**
 * Whether this value recursively conforms to the schema definitions it carries.
 *
 * This relation is universally true of values constructed by their model factories. The [world]
 * context fixes the canonical schema under which those carried definitions are interpreted.
 */
context(world: Assumptions)
internal fun Value.Output.conformsToSchema(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.Enum -> enumValue in type.values
        is Value.Simple -> true
        is Value.OutputList ->
            values.all { value -> value.conformsToSchema(typeExpr) }
        is Value.Object ->
            fieldValues.containingType == type &&
                fieldValues.values.all { value ->
                    value == null || value.conformsToSchema()
                }
    }

/**
 * Whether this input value recursively conforms to [typeExpr].
 *
 * Null conforms exactly at a nullable outer layer.
 */
context(world: Assumptions)
internal fun EngineInputData?.conformsToSchema(
    typeExpr: TypeExpr<Schema.InputType>,
): Boolean = conformsToInputSchemaType(typeExpr)

/**
 * Whether this output value recursively conforms to [typeExpr].
 *
 * Null conforms exactly at a nullable outer layer and [Value.Error] conforms to every output type
 * expression.
 */
context(world: Assumptions)
internal fun Value.Output?.conformsToSchema(
    typeExpr: TypeExpr<Schema.OutputType>,
): Boolean =
    conformsToSchemaType(typeExpr) &&
        (this == null || conformsToSchema())

/** Whether this argument tuple recursively conforms to [expectedType]. */
context(world: Assumptions)
internal fun Value.Arguments.conformsToSchema(
    expectedType: Schema.FieldArguments,
): Boolean = conformsToArgumentDefinition(expectedType)

/** Whether this key's arguments recursively conform to its output field. */
context(world: Assumptions)
internal fun ObjectEngineResult.Key.conformsToSchema(): Boolean {
    val keyArguments = arguments
    return keyArguments.conformsToArgumentDefinition(field.arguments)
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
                key.field.containingType == type &&
                    key.conformsToSchema() &&
                    value.conformsToResultSchemaType(key.field.typeExpr) &&
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
            containingType.values[name] == this
        is Double -> isFinite()
        is Int,
        is Boolean,
        is String,
        is Schema.ID,
        -> true
        else -> false
    }

private fun EngineInputData.conformsToInputObjectType(
    expectedType: Schema.InputObjectType,
): Boolean {
    val fieldValues = asEngineInputObjectDataOrNull() ?: return false
    if (
        expectedType.fields.values.any { field ->
            !field.typeExpr.isNullable &&
                field.defaultValue == Value.Default.Absent &&
                field.name !in fieldValues
        }
    ) {
        return false
    }
    return fieldValues.all { (fieldName, value) ->
        val field = expectedType.fields[fieldName] ?: return@all false
        value.conformsToInputSchemaType(field.typeExpr)
    }
}

internal fun EngineInputData?.conformsToInputSchemaType(
    typeExpr: TypeExpr<Schema.InputType>,
): Boolean =
    when {
        this == null -> typeExpr.isNullable
        typeExpr is TypeExpr.List ->
            asEngineInputListDataOrNull()
                ?.all { value -> value.conformsToInputSchemaType(typeExpr.elementType) }
                ?: false
        typeExpr is TypeExpr.Named ->
            when (val expectedType = typeExpr.baseType) {
                Schema.IntType -> this is Int
                Schema.FloatType -> this is Double && isFinite()
                Schema.StringType -> this is String
                Schema.BooleanType -> this is Boolean
                Schema.IDType -> this is String
                is Schema.EnumType ->
                    this is String &&
                        this in expectedType.values
                is Schema.InputObjectType ->
                    conformsToInputObjectType(expectedType)
            }
        else -> false
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

internal fun Value.Output?.conformsToSchemaType(
    typeExpr: TypeExpr<Schema.OutputType>,
): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        Value.Error -> true
        is Value.OutputList ->
            typeExpr is TypeExpr.List &&
                typeExpr.elementType.canContainPure(this.typeExpr)
        is Value.Typed ->
            typeExpr is TypeExpr.Named &&
                when (val expected = typeExpr.baseType) {
                    is Schema.CompositeType ->
                        type is Schema.ObjectType && type in expected.possibleTypes
                    else -> expected == type
                }
        is Value.Simple ->
            typeExpr is TypeExpr.Named && typeExpr.baseType == type
    }

internal fun EngineResult?.conformsToResultSchemaType(
    typeExpr: TypeExpr<Schema.OutputType>,
): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        ErrorEngineResult -> true
        is ObjectEngineResult ->
            if (typeExpr is TypeExpr.Named) {
                val declaredType = typeExpr.baseType
                declaredType is Schema.CompositeType && type in declaredType.possibleTypes
            } else {
                false
            }
        is ListEngineResult ->
            typeExpr is TypeExpr.List &&
                typeExpr.elementType.canContainPure(this.typeExpr)
        is Int -> typeExpr is TypeExpr.Named && typeExpr.baseType == Schema.IntType
        is Double ->
            isFinite() &&
                typeExpr is TypeExpr.Named &&
                typeExpr.baseType == Schema.FloatType
        is String -> typeExpr is TypeExpr.Named && typeExpr.baseType == Schema.StringType
        is Boolean -> typeExpr is TypeExpr.Named && typeExpr.baseType == Schema.BooleanType
        is Schema.ID -> typeExpr is TypeExpr.Named && typeExpr.baseType == Schema.IDType
        is Schema.EnumValue ->
            typeExpr is TypeExpr.Named &&
                typeExpr.baseType == containingType &&
                containingType.values[name] == this
        else -> false
    }

internal fun EngineResult.conformsToAccessResult(): Boolean =
    this is Boolean || this == ErrorEngineResult
