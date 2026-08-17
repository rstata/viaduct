package model.testing

import model.Schema

internal fun GJSchema.nodeBridgeType(nodeType: Schema.CompositeType): Schema.ObjectType =
    type(nodeBridgeTypeName(nodeType)) as Schema.ObjectType

internal fun GJSchema.nodeBridgeFieldOrNull(
    field: Schema.OutputField,
): Schema.OutputField? = field.takeIf(::isLoweredNodeField)
