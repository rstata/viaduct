package model.lowering

import graphql.schema.GraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.builder.DefinitionBuilder
import viaduct.graphql.schema.builder.InterfaceTypeBuilder
import viaduct.graphql.schema.builder.ObjectTypeBuilder
import viaduct.graphql.schema.builder.OutputFieldBuilder
import viaduct.graphql.schema.builder.TypeExprBuilder
import viaduct.graphql.schema.builder.ViaductSchemaBuilder
import viaduct.graphql.schema.builder.ViaductSchemaBuilderFilter
import viaduct.graphql.schema.graphqljava.graphqlValidate
import viaduct.graphql.schema.graphqljava.viaductSchema
import viaduct.graphql.schema.isNode
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationContext

/**
 * Creates qplan's field-resolution schema without modifying [source].
 */
internal fun lowerSchema(source: GraphQLSchema): ViaductSchema =
    lowerSchema(source.viaductSchema())

/**
 * Creates qplan's field-resolution schema without modifying [source].
 */
internal fun lowerSchema(source: ViaductSchema): ViaductSchema {
    val builder =
        ViaductSchemaBuilder.filteredCopy(
            source,
            object : ViaductSchemaBuilderFilter {
                override fun filterField(source: ViaductSchema.Field): Boolean =
                    !source.type.baseTypeDef.isNode
            },
        )
    val context = SchemaLoweringContext(source, builder)
    val errors =
        SchemaValidator(
            phases =
                listOf(
                    listOf(
                        ReservedNamesRule(),
                        NodeBridgeTypeRule(),
                        NodeBridgeFieldRule(),
                        NodeFieldRule(),
                        TypenameProxyRule(),
                    ),
                ),
        ).validate(context)
    require(errors.isEmpty()) {
        errors.joinToString(separator = "\n") { error ->
            "${error.message} (${error.location})"
        }
    }

    val lowered = builder.build()
    val graphQLErrors = graphqlValidate(lowered)
    require(graphQLErrors.isEmpty()) {
        graphQLErrors.joinToString(
            prefix = "Lowered schema is not GraphQL-valid:\n",
            separator = "\n",
        ) { it.message }
    }
    return lowered
}

internal class SchemaLoweringContext(
    source: ViaductSchema,
    val builder: ViaductSchemaBuilder,
) : ValidationContext(source) {
    private val bridgeBuilders = mutableMapOf<String, DefinitionBuilder>()

    fun bridgeBuilder(source: ViaductSchema.OutputRecord): DefinitionBuilder =
        bridgeBuilders.getOrPut(source.name) {
            val result: DefinitionBuilder =
                when (source) {
                    is ViaductSchema.Interface ->
                        InterfaceTypeBuilder(nodeBridgeTypeName(source.name))
                    is ViaductSchema.Object ->
                        ObjectTypeBuilder(nodeBridgeTypeName(source.name))
                    else -> error("Node type ${source.name} has an unsupported kind")
                }
            builder.addDefinition(result)
            result
        }
}

internal fun DefinitionBuilder.addInterface(name: String) {
    when (this) {
        is InterfaceTypeBuilder -> addInterface(name)
        is ObjectTypeBuilder -> addInterface(name)
        else -> error("${this::class.simpleName} cannot implement an interface")
    }
}

internal fun DefinitionBuilder.addField(field: OutputFieldBuilder) {
    when (this) {
        is InterfaceTypeBuilder -> addField(field)
        is ObjectTypeBuilder -> addField(field)
        else -> error("${this::class.simpleName} cannot own an output field")
    }
}

internal fun ViaductSchema.TypeExpr<*>.copyWithBaseType(
    baseTypeName: String,
): TypeExprBuilder {
    var result = TypeExprBuilder(baseTypeName, baseTypeNullable)
    for (depth in listDepth - 1 downTo 0) {
        result = result.list(nullableAtDepth(depth))
    }
    return result
}
