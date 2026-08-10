package model.testing

import graphql.schema.GraphQLFieldsContainer
import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.retarget
import model.selectionForestOf

internal fun GJSchema.nodeBridgeType(nodeType: Schema.CompositeType): Schema.ObjectType =
    type(nodeBridgeTypeName(nodeType)) as Schema.ObjectType

internal fun GJSchema.nodeBridgeFieldOrNull(
    field: Schema.OutputField,
): Schema.OutputField? {
    val sourceContainer =
        graphQLSchema.getType(field.containingType.typeName) as? GraphQLFieldsContainer
            ?: return null
    if (sourceContainer.getFieldDefinition(field.fieldName) == null) return null
    return field.containingType.fields[nodeBridgeFieldName(field)]
}

internal fun GJSchema.nodeBridgePayloadField(
    sourceField: Schema.OutputField,
): Schema.ObjectField {
    val bridgeField = nodeBridgeFieldOrNull(sourceField)
        ?: throw IllegalArgumentException(
            "${sourceField.containingType.typeName}/${sourceField.fieldName} is not node-valued",
        )
    val bridgeType = bridgeField.typeExpr.baseType as Schema.ObjectType
    return objectField(bridgeType.typeName, NODE_BRIDGE_PAYLOAD_FIELD)
}

internal fun GJSchema.lowerNodeSelections(selections: SelectionForest): SelectionForest =
    selections.flatMap { selection ->
        val loweredSubselections = lowerNodeSelections(selection.subselections)
        val bridgeField = nodeBridgeFieldOrNull(selection.key.field)
        if (bridgeField == null) {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = loweredSubselections,
                ),
            )
        } else {
            val bridgeType = bridgeField.typeExpr.baseType as Schema.ObjectType
            val payloadField = objectField(bridgeType.typeName, NODE_BRIDGE_PAYLOAD_FIELD)
            selectionForestOf(
                Selection.of(
                    key =
                        Value.Key.of(
                            field = bridgeField,
                            arguments = selection.key.arguments.retarget(bridgeField),
                        ),
                    possibleTypes = selection.possibleTypes,
                    subselections =
                        selectionForestOf(
                            Selection.of(
                                key = Value.Key.of(payloadField, emptyMap()),
                                possibleTypes = setOf(bridgeType),
                                subselections = loweredSubselections,
                            ),
                        ),
                ),
            )
        }
    }

internal fun GJSchema.lowerNodeFragment(fragment: Fragment): Fragment =
    Fragment.of(
        nominalType = fragment.nominalType,
        subselections = lowerNodeSelections(fragment.subselections),
    )

internal fun GJSchema.lowerNodeKeyPath(path: List<Value.Key>): List<Value.Key> =
    path.flatMap { key ->
        val bridgeField = nodeBridgeFieldOrNull(key.field)
        if (bridgeField == null) {
            listOf(key)
        } else {
            val bridgeType = bridgeField.typeExpr.baseType as Schema.ObjectType
            val payloadField = objectField(bridgeType.typeName, NODE_BRIDGE_PAYLOAD_FIELD)
            listOf(
                Value.Key.of(
                    field = bridgeField,
                    arguments = key.arguments.retarget(bridgeField),
                ),
                Value.Key.of(payloadField, emptyMap()),
            )
        }
    }
