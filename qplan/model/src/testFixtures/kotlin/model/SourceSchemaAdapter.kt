package model

import model.testing.GJSchema

/**
 * Explicit source-to-canonical adapter for fixture composition boundaries.
 *
 * Semantic code and tests should use [Schema.field] and [Schema.objectField] with canonical model
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
    ): Schema.OutputField =
        if (schema is GJSchema) {
            schema.fieldFromSource(typeName, fieldName)
        } else {
            schema.field(typeName, fieldName)
        }

    /** Returns the source GraphQL output type represented by a canonical fixture field. */
    fun typeExpr(field: Schema.OutputField): TypeExpr<Schema.OutputType> =
        if (schema is GJSchema) {
            schema.sourceTypeExpr(field)
        } else {
            field.typeExpr
        }

    /** Lowers a source-shaped output for storage at a canonical fixture field. */
    fun lowerOutput(
        field: Schema.OutputField,
        output: EngineOutputData?,
    ): EngineOutputData? =
        if (schema is GJSchema) {
            schema.lowerSourceOutput(field, output)
        } else {
            output
        }
    }
