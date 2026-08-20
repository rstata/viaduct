package model.testing

import model.Schema
import model.requireType

internal fun GJSchema.nodeBridgeType(
    nodeType: Schema.CompositeTypeDef,
): Schema.CompositeTypeDef =
    requireType(nodeBridgeTypeName(nodeType)) as Schema.CompositeTypeDef

internal fun GJSchema.nodeBridgeFieldOrNull(
    field: Schema.Field,
): Schema.Field? = field.takeIf(::isLoweredNodeField)
