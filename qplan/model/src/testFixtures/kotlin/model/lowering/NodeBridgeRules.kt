package model.lowering

import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeReference
import model.qplanEngineObjectDataTypeKey
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.builder.AppliedDirectiveBuilder
import viaduct.graphql.schema.builder.ArgumentBuilder
import viaduct.graphql.schema.builder.InterfaceTypeExtensionBuilder
import viaduct.graphql.schema.builder.ObjectTypeExtensionBuilder
import viaduct.graphql.schema.builder.OutputFieldBuilder
import viaduct.graphql.schema.builder.TypeExprBuilder
import viaduct.graphql.schema.isNode
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationRule

internal class NodeBridgeTypeRule :
    ValidationRule(
        id = "QPlanNodeBridgeTypes",
        description = "Adds one same-kind bridge for every Node output record",
    ) {
    override fun visitInterface(
        ctx: ValidationContext,
        iface: ViaductSchema.Interface,
    ) = addBridge(ctx, iface)

    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object,
    ) = addBridge(ctx, obj)

    private fun addBridge(
        ctx: ValidationContext,
        source: ViaductSchema.OutputRecord,
    ) {
        if (!source.isNode) return
        val bridge = (ctx as SchemaLoweringContext).bridgeBuilder(source)
        source.supers
            .filter { it.isNode }
            .forEach { bridge.addInterface(nodeBridgeTypeName(it.name)) }
    }
}

internal class NodeBridgeFieldRule :
    ValidationRule(
        id = "QPlanNodeBridgeFields",
        description = "Adds id and node fields to every Node bridge",
    ) {
    override fun visitInterface(
        ctx: ValidationContext,
        iface: ViaductSchema.Interface,
    ) = addFields(ctx, iface)

    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object,
    ) = addFields(ctx, obj)

    private fun addFields(
        ctx: ValidationContext,
        source: ViaductSchema.OutputRecord,
    ) {
        if (!source.isNode) return
        val bridge = (ctx as SchemaLoweringContext).bridgeBuilder(source)
        bridge.addField(
            OutputFieldBuilder(
                NODE_BRIDGE_ID_FIELD,
                TypeExprBuilder("ID"),
            ),
        )
        bridge.addField(
            OutputFieldBuilder(
                NODE_BRIDGE_PAYLOAD_FIELD,
                TypeExprBuilder(source.name),
            ),
        )
        if (bridge is viaduct.graphql.schema.builder.ObjectTypeBuilder) {
            bridge.put(
                qplanEngineObjectDataTypeKey,
                GraphQLObjectType
                    .newObject()
                    .name(nodeBridgeTypeName(source.name))
                    .field(
                        GraphQLFieldDefinition
                            .newFieldDefinition()
                            .name(NODE_BRIDGE_ID_FIELD)
                            .type(GraphQLTypeReference.typeRef("ID")),
                    ).field(
                        GraphQLFieldDefinition
                            .newFieldDefinition()
                            .name(NODE_BRIDGE_PAYLOAD_FIELD)
                            .type(GraphQLTypeReference.typeRef(source.name)),
                    ).build(),
            )
        }
    }
}

internal class NodeFieldRule :
    ValidationRule(
        id = "QPlanNodeFields",
        description = "Replaces Node-valued source fields with bridge-valued producers",
    ) {
    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field,
    ) {
        if (!field.type.baseTypeDef.isNode) return

        val replacement =
            OutputFieldBuilder(
                nodeBridgeFieldName(field.name),
                field.type.copyWithBaseType(
                    nodeBridgeTypeName(field.type.baseTypeDef.name),
                ),
            ).description(field.description)
        field.args.map(::copyArgument).forEach(replacement::addArgument)
        field.appliedDirectives
            .map(::copyAppliedDirective)
            .forEach(replacement::addAppliedDirective)

        val extension =
            when (val owner = field.containingDef) {
                is ViaductSchema.Interface ->
                    InterfaceTypeExtensionBuilder(owner.name)
                        .addField(replacement)
                        .sourceLocation(field.containingExtension.sourceLocation)
                is ViaductSchema.Object ->
                    ObjectTypeExtensionBuilder(owner.name)
                        .addField(replacement)
                        .sourceLocation(field.containingExtension.sourceLocation)
                else -> error("Node-valued field ${owner.name}.${field.name} has an invalid owner")
            }
        (ctx as SchemaLoweringContext).builder.addDefinition(extension)
    }

    private fun copyArgument(source: ViaductSchema.FieldArg): ArgumentBuilder =
        ArgumentBuilder(
            source.name,
            source.type.copyWithBaseType(source.type.baseTypeDef.name),
        ).description(source.description)
            .apply {
                if (source.hasDefault) {
                    defaultValue(source.defaultValue)
                }
                source.appliedDirectives
                    .map(::copyAppliedDirective)
                    .forEach(::addAppliedDirective)
            }

    private fun copyAppliedDirective(
        source: ViaductSchema.AppliedDirective<*>,
    ): AppliedDirectiveBuilder =
        AppliedDirectiveBuilder(source.name)
            .apply {
                source.arguments.forEach { (name, value) ->
                    addArgument(name, value)
                }
            }
}
