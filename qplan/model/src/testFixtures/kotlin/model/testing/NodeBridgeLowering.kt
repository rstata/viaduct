package model.testing

import model.lowering.nodeBridgeTypeName
import viaduct.graphql.schema.ViaductSchema

import model.requireType

internal fun GJSchema.nodeBridgeType(
    nodeType: ViaductSchema.CompositeTypeDef,
): ViaductSchema.CompositeTypeDef =
    requireType(nodeBridgeTypeName(nodeType.name)) as ViaductSchema.CompositeTypeDef

internal fun GJSchema.nodeBridgeFieldOrNull(
    field: ViaductSchema.Field,
): ViaductSchema.Field? = field.takeIf(::isLoweredNodeField)
