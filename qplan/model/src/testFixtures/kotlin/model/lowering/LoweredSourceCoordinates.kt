package model.lowering

import model.requireOutputType
import viaduct.graphql.schema.ViaductSchema

/**
 * Resolves one source GraphQL field coordinate to its canonical lowered field.
 *
 * Node-valued source fields are absent from the lowered schema and resolve through their
 * deterministically named bridge producers. Typename is a source pseudo-field represented by an
 * ordinary synthetic field after lowering.
 */
internal fun ViaductSchema.loweredFieldFromSourceCoordinate(
    sourceTypeName: String,
    sourceFieldName: String,
): ViaductSchema.Field {
    requireSourceName(sourceTypeName, sourceFieldName)
    if (sourceFieldName == "__typename") {
        return loweredTypenameField(sourceTypeName)
    }

    val owner =
        types[sourceTypeName] as? ViaductSchema.OutputRecord
            ?: throw invalidSourceField(sourceTypeName, sourceFieldName)
    return owner.field(sourceFieldName)
        ?: owner.field(nodeBridgeFieldName(sourceFieldName))
        ?: throw invalidSourceField(sourceTypeName, sourceFieldName)
}

/** Resolves the bridge producer replacing one Node-valued source field. */
internal fun ViaductSchema.loweredNodeBridgeField(
    sourceTypeName: String,
    sourceFieldName: String,
): ViaductSchema.Field {
    requireSourceName(sourceTypeName, sourceFieldName)
    val owner =
        types[sourceTypeName] as? ViaductSchema.OutputRecord
            ?: throw invalidSourceField(sourceTypeName, sourceFieldName)
    return owner.field(nodeBridgeFieldName(sourceFieldName))
        ?: throw invalidSourceField(sourceTypeName, sourceFieldName)
}

/** Resolves the ordinary lowered field representing source `__typename` demand. */
internal fun ViaductSchema.loweredTypenameField(
    sourceTypeName: String,
): ViaductSchema.Field {
    requireSourceName(sourceTypeName, "__typename")
    val sourceType =
        types[sourceTypeName] as? ViaductSchema.CompositeTypeDef
            ?: throw invalidSourceField(sourceTypeName, "__typename")
    val ownerName =
        if (sourceType is ViaductSchema.Union) {
            ALL_SOURCE_OBJECTS_TYPE
        } else {
            sourceTypeName
        }
    val owner =
        types[ownerName] as? ViaductSchema.OutputRecord
            ?: throw invalidSourceField(sourceTypeName, "__typename")
    return owner.field(LOWERED_TYPENAME_FIELD)
        ?: throw invalidSourceField(sourceTypeName, "__typename")
}

/** Whether this lowered field is a producer replacing a Node-valued source field. */
internal fun ViaductSchema.Field.isLoweredNodeBridgeField(): Boolean =
    name.endsWith(NODE_BRIDGE_FIELD_SUFFIX) &&
        type.baseTypeDef.name.endsWith(NODE_BRIDGE_TYPE_SUFFIX)

/**
 * Returns the source output type represented by this canonical lowered field.
 *
 * Node bridge lowering preserves every wrapper and changes only the named base type.
 */
internal fun ViaductSchema.sourceTypeExpr(
    field: ViaductSchema.Field,
): ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> {
    if (!field.isLoweredNodeBridgeField()) {
        return field.type.requireOutputType()
    }
    val sourceTypeName =
        field.type.baseTypeDef.name.removeSuffix(NODE_BRIDGE_TYPE_SUFFIX)
    val sourceType =
        types[sourceTypeName] as? ViaductSchema.OutputTypeDef
            ?: error("Lowered node bridge field ${field.name} has no source type $sourceTypeName")
    return ViaductSchema.TypeExpr(
        sourceType,
        field.type.baseTypeNullable,
        field.type.listNullable,
    )
}

private fun requireSourceName(
    sourceTypeName: String,
    sourceFieldName: String,
) {
    if (
        sourceTypeName.contains(LOWERING_SYNTHETIC_NAME_TOKEN) ||
        sourceFieldName.contains(LOWERING_SYNTHETIC_NAME_TOKEN)
    ) {
        throw invalidSourceField(sourceTypeName, sourceFieldName)
    }
}

private fun invalidSourceField(
    sourceTypeName: String,
    sourceFieldName: String,
): IllegalArgumentException =
    IllegalArgumentException(
        "$sourceTypeName/$sourceFieldName is not a source GraphQL field",
    )
