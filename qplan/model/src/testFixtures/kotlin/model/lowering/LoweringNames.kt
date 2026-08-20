package model.lowering

import viaduct.graphql.schema.ViaductSchema

internal const val LOWERING_SYNTHETIC_NAME_TOKEN = "V_A"
internal const val ALL_SOURCE_OBJECTS_TYPE = "V_A_AllSourceObjects"
internal const val LOWERED_TYPENAME_FIELD = "V_A_typename"
internal const val NODE_BRIDGE_TYPE_SUFFIX = "_V_A_Bridge"
internal const val NODE_BRIDGE_FIELD_SUFFIX = "_V_A_node"
internal const val NODE_BRIDGE_ID_FIELD = "id"
internal const val NODE_BRIDGE_PAYLOAD_FIELD = "node"
internal const val TYPED_NODE_ID_PREFIX = "\$node:"
internal const val VIADUCT_IGNORE_SYMBOL = ViaductSchema.VIADUCT_IGNORE_SYMBOL

internal fun nodeBridgeTypeName(sourceTypeName: String): String =
    sourceTypeName + NODE_BRIDGE_TYPE_SUFFIX

internal fun nodeBridgeFieldName(sourceFieldName: String): String =
    sourceFieldName + NODE_BRIDGE_FIELD_SUFFIX
