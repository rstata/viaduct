package model

import viaduct.graphql.schema.ViaductSchema

import model.testing.GJSchema

/**
 * Explicit source-to-canonical adapter for fixture composition boundaries.
 *
 * Semantic code and tests should use [ViaductSchema.requireField] and [ViaductSchema.requireObjectField] with canonical model
 * coordinates. This adapter is reserved for inputs that are intentionally expressed in the
 * retained GraphQL source schema.
 */
class SourceSchemaAdapter(
    schema: ViaductSchema,
) {
    private val schema =
        requireNotNull(schema as? GJSchema) {
            "SourceSchemaAdapter requires the canonical source/lowered fixture schema pair"
        }

    /** Resolves a source GraphQL field to its canonical lowered fixture coordinate. */
    fun field(
        typeName: String,
        fieldName: String,
    ): ViaductSchema.Field = schema.fieldFromSource(typeName, fieldName)

    /** Returns the source GraphQL output type represented by a canonical fixture field. */
    fun typeExpr(field: ViaductSchema.Field): ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> =
        schema.sourceTypeExpr(field)

    /** Lowers a source-shaped output for storage at a canonical fixture field. */
    fun lowerOutput(
        field: ViaductSchema.Field,
        output: EngineOutputData?,
    ): EngineOutputData? = schema.lowerSourceOutput(field, output)
}
