package model.invariants

import model.Assumptions
import model.EngineResult
import model.EnumEngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.SimpleEngineResult
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
fun Value.Output.conformsToSchema(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.Enum -> enumValue in type.values
        is Value.Simple -> true
        is Value.OutputList ->
            values.all { value -> value.conformsToSchema(typeExpr) }
        is Value.Object ->
            fieldValues.containingType == type &&
                fieldValues.all { (key, value) ->
                    key.field.containingType == type &&
                        key.conformsToSchema() &&
                        value.conformsToSchema(key.field.typeExpr)
                }
    }

/**
 * Whether this input value recursively conforms to [typeExpr].
 *
 * Null conforms exactly at a nullable outer layer. [Value.Error] conforms to every input type
 * expression.
 */
context(world: Assumptions)
fun Value.Input?.conformsToSchema(
    typeExpr: TypeExpr<Schema.InputType>,
): Boolean = conformsToSchemaType(typeExpr)

/**
 * Whether this output value recursively conforms to [typeExpr].
 *
 * Null conforms exactly at a nullable outer layer and [Value.Error] conforms to every output type
 * expression.
 */
context(world: Assumptions)
fun Value.Output?.conformsToSchema(
    typeExpr: TypeExpr<Schema.OutputType>,
): Boolean =
    conformsToSchemaType(typeExpr) &&
        (this == null || conformsToSchema())

/** Whether this argument tuple recursively conforms to [expectedType]. */
context(world: Assumptions)
fun Value.Arguments.conformsToSchema(
    expectedType: Schema.FieldArguments,
): Boolean = conformsToArgumentDefinition(expectedType)

/** Whether this key's arguments recursively conform to its output field. */
context(world: Assumptions)
fun ObjectEngineResult.Key.conformsToSchema(): Boolean {
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
fun EngineResult.conformsToSchema(): Boolean =
    when (this) {
        ErrorEngineResult -> true
        is EnumEngineResult -> enumValue in type.values
        is SimpleEngineResult -> true
        is ObjectEngineResult ->
            keys.all { key ->
                val value = getCell(key).getValue().get()
                key.field.containingType == type &&
                    key.conformsToSchema() &&
                    value.conformsToSchemaType(key.field.typeExpr) &&
                    (value?.conformsToSchema() ?: true)
            }
        is ListEngineResult ->
            all { cell ->
                val value = cell.getValue().get()
                value.conformsToSchemaType(typeExpr) &&
                    (value?.conformsToSchema() ?: true)
            }
    }

private fun Value.InputObject.conformsToInputObjectType(
    expectedType: Schema.InputObjectType,
): Boolean =
    fieldValues.all { (fieldName, value) ->
        val field = expectedType.fields[fieldName] ?: return@all false
        value.conformsToSchemaType(field.typeExpr)
    }

internal fun Value.Input?.conformsToSchemaType(
    typeExpr: TypeExpr<Schema.InputType>,
): Boolean =
    when {
        this == null -> typeExpr.isNullable
        this == Value.Error -> true
        typeExpr is TypeExpr.List ->
            this is Value.InputList &&
                values.all { value -> value.conformsToSchemaType(typeExpr.elementType) }
        typeExpr is TypeExpr.Named ->
            when (val expectedType = typeExpr.baseType) {
                Schema.IntType -> this is Value.Int
                Schema.FloatType -> this is Value.Float
                Schema.StringType -> this is Value.String
                Schema.BooleanType -> this is Value.Boolean
                Schema.IDType -> this is Value.ID
                is Schema.EnumType ->
                    this is Value.Enum &&
                        enumValue in expectedType.values
                is Schema.InputObjectType ->
                    this is Value.InputObject &&
                        conformsToInputObjectType(expectedType)
            }
        else -> false
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

internal fun EngineResult?.conformsToSchemaType(
    typeExpr: TypeExpr<Schema.OutputType>,
): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        ErrorEngineResult -> true
        is SimpleEngineResult ->
            typeExpr is TypeExpr.Named && typeExpr.baseType == type
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
    }
