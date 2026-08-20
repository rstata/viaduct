package model.testing

import model.ObjectEngineResult
import graphql.language.NamedNode
import graphql.language.Node
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import model.Schema
import model.EngineErrorData
import model.EngineOutputData
import model.TypeExpr
import model.engineObjectDataOf
import model.requireField
import model.requireObjectField
import model.requireType
import model.schemaType
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.utils.GraphQLTypeRelation
import viaduct.graphql.utils.GraphQLTypeRelations

/**
 * A fixture pair of the GraphQL-visible source schema and the canonical decoded [Schema].
 *
 * Construct the reasoning world's one schema before its values and assumptions so every non-error
 * value is created through this exact canonical graph. The canonical graph may contain synthetic
 * node bridge types and fields absent from the retained GraphQL Java schema. [EngineErrorData] is
 * schema-independent. The retained source schema parses and validates GraphQL selections, ensuring
 * those inputs cannot name synthetic definitions.
 */
internal class GJSchema private constructor(
    internal val graphQLSchema: GraphQLSchema,
    internal val typeRelations: GraphQLTypeRelations,
    private val decodedSchema: Schema,
) : Schema by decodedSchema {
    internal fun sourceCompositeType(type: Schema.CompositeTypeDef): GraphQLCompositeType {
        require(requireType(type.name) == type) {
            "${type.name} is not canonical in this schema"
        }
        return graphQLSchema.getType(type.name) as? GraphQLCompositeType
            ?: throw IllegalArgumentException("${type.name} is not a source composite type")
    }

    /** The canonical concrete object types available to fixture registry lowering. */
    internal val objectTypes: List<Schema.Object>
        get() =
            graphQLSchema.allTypesAsList
                .filterIsInstance<GraphQLObjectType>()
                .filterNot { it.name.startsWith("__") }
                .map { requireType(it.name) as Schema.Object }

    internal fun fieldFromSource(
        typeName: String,
        fieldName: String,
    ): Schema.Field {
        if (fieldName == "__typename") {
            val sourceType = graphQLSchema.getType(typeName)
            val canonicalOwner =
                if (sourceType is GraphQLUnionType) {
                    ALL_SOURCE_OBJECTS_TYPE
                } else {
                    typeName
                }
            return requireField(canonicalOwner, LOWERED_TYPENAME_FIELD)
        }
        val sourceField =
            (graphQLSchema.getType(typeName) as? GraphQLFieldsContainer)
                ?.getFieldDefinition(fieldName)
                ?: throw IllegalArgumentException("$typeName/$fieldName is not a source GraphQL field")
        val canonicalName =
            if (sourceField.isNodeValued()) {
                nodeBridgeFieldName(fieldName)
            } else {
                fieldName
            }
        return requireField(typeName, canonicalName)
    }

    internal fun sourceTypeExpr(field: Schema.Field): TypeExpr<Schema.OutputTypeDef> {
        val sourceField = sourceField(field) ?: return field.type
        return decodeModelOutputType(sourceField.type, this)
    }

    internal fun isLoweredNodeField(field: Schema.Field): Boolean =
        sourceField(field)?.isNodeValued() == true &&
            field.name.endsWith(NODE_BRIDGE_FIELD_SUFFIX)

    internal fun lowerSourceOutput(
        field: Schema.Field,
        output: EngineOutputData?,
    ): EngineOutputData? {
        if (!isLoweredNodeField(field)) return output
        return lowerNodeReferences(
            output = output,
            sourceTypeExpr = sourceTypeExpr(field),
            bridgeTypeExpr = field.type,
        )
    }

    private fun sourceField(
        field: Schema.Field,
    ): GraphQLFieldDefinition? {
        val sourceContainer =
            graphQLSchema.getType(field.containingDef.name) as? GraphQLFieldsContainer
                ?: return null
        sourceContainer.getFieldDefinition(field.name)?.let { return it }
        if (!field.name.endsWith(NODE_BRIDGE_FIELD_SUFFIX)) return null
        return sourceContainer
            .getFieldDefinition(field.name.removeSuffix(NODE_BRIDGE_FIELD_SUFFIX))
            ?.takeIf { it.isNodeValued() }
    }

    private fun GraphQLFieldDefinition.isNodeValued(): Boolean {
        val node = graphQLSchema.getType("Node") as? GraphQLInterfaceType ?: return false
        val output = GraphQLTypeUtil.unwrapAll(type) as? GraphQLImplementingType ?: return false
        return typeRelations.relationUnwrapped(node, output) in
            setOf(GraphQLTypeRelation.Same, GraphQLTypeRelation.WiderThan)
    }

    private fun lowerNodeReferences(
        output: EngineOutputData?,
        sourceTypeExpr: TypeExpr<Schema.OutputTypeDef>,
        bridgeTypeExpr: TypeExpr<Schema.OutputTypeDef>,
    ): EngineOutputData? =
        when {
            output == null || output == EngineErrorData -> output
            sourceTypeExpr.isList && bridgeTypeExpr.isList -> {
                require(output is List<*>) {
                    "Node-list field resolver did not return a list"
                }
                val sourceElementType = checkNotNull(sourceTypeExpr.unwrapList())
                val bridgeElementType = checkNotNull(bridgeTypeExpr.unwrapList())
                output.map { value ->
                    lowerNodeReferences(
                        output = value,
                        sourceTypeExpr = sourceElementType,
                        bridgeTypeExpr = bridgeElementType,
                    )
                }
            }
            !sourceTypeExpr.isList && !bridgeTypeExpr.isList -> {
                require(output is EngineObjectData.Sync) {
                    "Node field resolver did not return a node reference"
                }
                val outputType = output.schemaType
                val idField = requireObjectField(outputType.name, "id")
                val id = output.get(idField.name)
                require(id != EngineErrorData && id is String) {
                    "Node reference ${outputType.name}/id must contain a non-error ID"
                }
                val bridgeType = bridgeTypeExpr.baseTypeDef as Schema.Object
                val bridgeId = requireObjectField(bridgeType.name, NODE_BRIDGE_ID_FIELD)
                engineObjectDataOf(
                    schemaType = bridgeType,
                    fields =
                        mapOf(
                            bridgeId.name to
                                "$TYPED_NODE_ID_PREFIX${outputType.name.length}:" +
                                    "${outputType.name}$id",
                        ),
                )
            }
            else -> error("Node and bridge type expressions have different list shapes")
        }

    companion object {
        private val STANDARD_SCALAR_NAMES = setOf("Int", "Float", "String", "Boolean", "ID")
        private val STANDARD_DIRECTIVE_NAMES =
            setOf("skip", "include", "deprecated", "specifiedBy", "oneOf")

        @JvmStatic
        fun fromSDL(schemaSDL: String): GJSchema {
            val graphQLSchema = parseSchema(schemaSDL)
            val typeRelations = GraphQLTypeRelations(graphQLSchema)
            validateNodeFieldCovariance(graphQLSchema, typeRelations)
            return GJSchema(
                graphQLSchema = graphQLSchema,
                typeRelations = typeRelations,
                decodedSchema = GJSchemaDecoder(graphQLSchema, typeRelations).decode(),
            )
        }

        private fun parseSchema(schemaSDL: String): GraphQLSchema {
            validateReservedNames(schemaSDL)
            val registry = SchemaParser().parse(schemaSDL)
            val nonStandardScalars =
                (
                    registry.scalars().keys +
                        registry.scalarTypeExtensions().keys
                ) - STANDARD_SCALAR_NAMES
            require(nonStandardScalars.isEmpty()) {
                "Non-standard scalar types are outside the model: " +
                    nonStandardScalars.sorted().joinToString()
            }

            val nonStandardDirectives =
                registry.directiveDefinitions.keys - STANDARD_DIRECTIVE_NAMES
            require(nonStandardDirectives.isEmpty()) {
                "Non-standard directives are outside the model: " +
                    nonStandardDirectives.sorted().joinToString()
            }

            return UnExecutableSchemaGenerator
                .makeUnExecutableSchema(registry)
        }

        private fun validateNodeFieldCovariance(
            schema: GraphQLSchema,
            typeRelations: GraphQLTypeRelations,
        ) {
            val node = schema.getType("Node") as? GraphQLInterfaceType ?: return
            val interfaces =
                schema.allTypesAsList
                    .filterIsInstance<GraphQLInterfaceType>()
                    .filterNot { it.name.startsWith("__") }

            schema.allTypesAsList
                .filterIsInstance<GraphQLObjectType>()
                .filterNot { it.name.startsWith("__") }
                .forEach { objectType ->
                    interfaces
                        .filter { interfaceType ->
                            typeRelations.relationUnwrapped(interfaceType, objectType) ==
                                GraphQLTypeRelation.WiderThan
                        }.forEach { interfaceType ->
                            interfaceType.fieldDefinitions.forEach { interfaceField ->
                                val interfaceOutput =
                                    interfaceField.nodeOutputName(typeRelations, node)
                                        ?: return@forEach
                                val objectOutput =
                                    objectType
                                        .getFieldDefinition(interfaceField.name)
                                        ?.nodeOutputName(typeRelations, node)
                                        ?: return@forEach
                                require(objectOutput == interfaceOutput) {
                                    "Node-valued interface field covariance is outside model " +
                                        "lowering without a bridge hierarchy: " +
                                        "${interfaceType.name}.${interfaceField.name}: " +
                                        "$interfaceOutput, ${objectType.name}." +
                                        "${interfaceField.name}: $objectOutput"
                                }
                            }
                        }
                }
        }

        private fun GraphQLFieldDefinition.nodeOutputName(
            typeRelations: GraphQLTypeRelations,
            node: GraphQLInterfaceType,
        ): String? {
            val output = GraphQLTypeUtil.unwrapAll(type) as? GraphQLImplementingType ?: return null
            return output.name.takeIf {
                typeRelations.relationUnwrapped(node, output) in
                    setOf(GraphQLTypeRelation.Same, GraphQLTypeRelation.WiderThan)
            }
        }

        private fun validateReservedNames(schemaSDL: String) {
            val invalidNames = linkedSetOf<String>()
            val ignoredNames = linkedSetOf<String>()

            fun visit(node: Node<*>) {
                val name = (node as? NamedNode<*>)?.name
                if (name != null && name.contains(LOWERING_SYNTHETIC_NAME_TOKEN)) {
                    invalidNames.add(name)
                }
                if (name == VIADUCT_IGNORE_SYMBOL) {
                    ignoredNames.add(name)
                }
                node.children.forEach(::visit)
            }

            Parser.parse(schemaSDL).children.forEach(::visit)
            require(invalidNames.isEmpty()) {
                "Source schema names cannot contain reserved token " +
                    "$LOWERING_SYNTHETIC_NAME_TOKEN: ${invalidNames.sorted().joinToString()}"
            }
            require(ignoredNames.isEmpty()) {
                "Source schema names cannot use reserved symbol $VIADUCT_IGNORE_SYMBOL"
            }
        }
    }
}
