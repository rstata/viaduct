package model.testing

import model.Schema
import model.requireType

internal fun GJSchema.nodeBridgeType(nodeType: Schema.CompositeTypeDef): Schema.Object =
    requireType(nodeBridgeTypeName(nodeType)) as Schema.Object

internal fun GJSchema.nodeBridgeFieldOrNull(
    field: Schema.Field,
): Schema.Field? = field.takeIf(::isLoweredNodeField)
