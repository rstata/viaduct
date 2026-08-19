package model

import model.testing.GJSchema

/**
 * Explicit source-to-canonical adapter for fixture composition boundaries.
 *
 * Semantic code and tests should use [Schema.requireField] and [Schema.requireObjectField] with canonical model
 * coordinates. This adapter is reserved for inputs that are intentionally expressed in the
 * retained GraphQL source schema.
 */
class SourceSchemaAdapter(
    private val schema: Schema,
) {
    /** Resolves a source GraphQL field to its canonical lowered fixture coordinate. */
    fun field(
        typeName: String,
        fieldName: String,
    ): Schema.Field =
        if (schema is GJSchema) {
            schema.fieldFromSource(typeName, fieldName)
        } else {
            schema.requireField(typeName, fieldName)
        }

    /** Returns the source GraphQL output type represented by a canonical fixture field. */
    fun typeExpr(field: Schema.Field): TypeExpr<Schema.OutputTypeDef> =
        if (schema is GJSchema) {
            schema.sourceTypeExpr(field)
        } else {
            field.type
        }

    /** Lowers a source-shaped output for storage at a canonical fixture field. */
    fun lowerOutput(
        field: Schema.Field,
        output: EngineOutputData?,
    ): EngineOutputData? =
        if (schema is GJSchema) {
            schema.lowerSourceOutput(field, output)
        } else {
            output
        }
    }
