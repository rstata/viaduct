package model.invariants

import model.Assumptions
import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.canContainPure

/**
 * Whether this value recursively conforms to the schema definitions it carries.
 *
 * This relation is universally true of values constructed by their model factories. The [world]
 * context fixes the canonical schema under which those carried definitions are interpreted.
 */
context(world: Assumptions)
fun Value.conformsToSchema(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.Enum -> enumValue in type.values
        is Value.Simple -> true
        is Value.InputList ->
            values.all { value -> value.conformsToSchema(typeExpr) }
        is Value.OutputList ->
            values.all { value -> value.conformsToSchema(typeExpr) }
        is Value.InputObject -> inputLikeConformsToSchema()
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
): Boolean =
    conformsToSchemaType(typeExpr) &&
        (this == null || (this as Value).conformsToSchema())

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
        (this == null || (this as Value).conformsToSchema())

/** Whether this argument tuple recursively conforms to its field-argument definition. */
context(world: Assumptions)
fun Value.Arguments.conformsToSchema(): Boolean =
    inputLikeConformsToSchema()

/** Whether this key's arguments recursively conform to its output field. */
context(world: Assumptions)
fun Value.Key.conformsToSchema(): Boolean {
    val keyArguments = arguments
    return keyArguments.type == field.arguments &&
        (keyArguments !is Value.Arguments || keyArguments.conformsToSchema())
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
        Value.Error -> true
        is Value.Simple -> (this as Value).conformsToSchema()
        is EngineResult.Object ->
            keys.all { key ->
                val value = getCell(key).getValue().get()
                key.field.containingType == type &&
                    key.conformsToSchema() &&
                    value.conformsToSchemaType(key.field.typeExpr) &&
                    (value?.conformsToSchema() ?: true)
            }
        is EngineResult.List ->
            all { cell ->
                val value = cell.getValue().get()
                value.conformsToSchemaType(typeExpr) &&
                    (value?.conformsToSchema() ?: true)
            }
    }

context(world: Assumptions)
private fun Value.InputObjectLike.inputLikeConformsToSchema(): Boolean =
    fieldValues.containingType == type &&
        fieldValues.all { (fieldName, value) ->
            val field = type.fields.getValue(fieldName)
            value.conformsToSchema(field.typeExpr)
        }

internal fun Value.Input?.conformsToSchemaType(
    typeExpr: TypeExpr<Schema.InputType>,
): Boolean =
    when (this) {
        null -> typeExpr.isNullable
        Value.Error -> true
        is Value.InputList ->
            typeExpr is TypeExpr.List &&
                typeExpr.elementType.canContainPure(this.typeExpr)
        is Value.Typed ->
            typeExpr is TypeExpr.Named && typeExpr.baseType == type
        else ->
            typeExpr is TypeExpr.Named &&
                typeExpr.baseType == (this as Value.Simple).type
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
        Value.Error -> true
        is Value.Simple ->
            typeExpr is TypeExpr.Named && typeExpr.baseType == type
        is EngineResult.Object ->
            if (typeExpr is TypeExpr.Named) {
                val declaredType = typeExpr.baseType
                declaredType is Schema.CompositeType && type in declaredType.possibleTypes
            } else {
                false
            }
        is EngineResult.List ->
            typeExpr is TypeExpr.List &&
                typeExpr.elementType.canContainPure(this.typeExpr)
    }
