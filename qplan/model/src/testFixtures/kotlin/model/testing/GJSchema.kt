package model.testing

import model.ObjectEngineResult

import graphql.language.NamedNode
import graphql.language.Node
import graphql.parser.Parser
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
import model.schemaType
import viaduct.engine.api.EngineObjectData

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
    private val decodedSchema: Schema,
) : Schema by decodedSchema {
    /** The canonical concrete object types available to fixture registry lowering. */
    internal val objectTypes: List<Schema.ObjectType>
        get() =
            graphQLSchema.allTypesAsList
                .filterIsInstance<GraphQLObjectType>()
                .filterNot { it.name.startsWith("__") }
                .map { type(it.name) as Schema.ObjectType }

    internal fun fieldFromSource(
        typeName: String,
        fieldName: String,
    ): Schema.OutputField {
        if (fieldName == "__typename") {
            val sourceType = graphQLSchema.getType(typeName)
            val canonicalOwner =
                if (sourceType is GraphQLUnionType) {
                    TYPENAME_TOP_TYPE
                } else {
                    typeName
                }
            return field(canonicalOwner, LOWERED_TYPENAME_FIELD)
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
        return field(typeName, canonicalName)
    }

    internal fun sourceTypeExpr(field: Schema.OutputField): TypeExpr<Schema.OutputType> {
        val sourceField = sourceField(field) ?: return field.typeExpr
        return decodeModelOutputType(sourceField.type, this)
    }

    internal fun isLoweredNodeField(field: Schema.OutputField): Boolean =
        sourceField(field)?.isNodeValued() == true &&
            field.fieldName.endsWith(NODE_BRIDGE_FIELD_SUFFIX)

    internal fun lowerSourceOutput(
        field: Schema.OutputField,
        output: EngineOutputData?,
    ): EngineOutputData? {
        if (!isLoweredNodeField(field)) return output
        return lowerNodeReferences(
            output = output,
            sourceTypeExpr = sourceTypeExpr(field),
            bridgeTypeExpr = field.typeExpr,
        )
    }

    private fun sourceField(
        field: Schema.OutputField,
    ): GraphQLFieldDefinition? {
        val sourceContainer =
            graphQLSchema.getType(field.containingType.typeName) as? GraphQLFieldsContainer
                ?: return null
        sourceContainer.getFieldDefinition(field.fieldName)?.let { return it }
        if (!field.fieldName.endsWith(NODE_BRIDGE_FIELD_SUFFIX)) return null
        return sourceContainer
            .getFieldDefinition(field.fieldName.removeSuffix(NODE_BRIDGE_FIELD_SUFFIX))
            ?.takeIf { it.isNodeValued() }
    }

    private fun GraphQLFieldDefinition.isNodeValued(): Boolean {
        val node = graphQLSchema.getType("Node") as? GraphQLInterfaceType ?: return false
        val output = GraphQLTypeUtil.unwrapAll(type) as? GraphQLImplementingType ?: return false
        return output.name == node.name ||
            implementsInterface(graphQLSchema, node.name, output)
    }

    private fun lowerNodeReferences(
        output: EngineOutputData?,
        sourceTypeExpr: TypeExpr<Schema.OutputType>,
        bridgeTypeExpr: TypeExpr<Schema.OutputType>,
    ): EngineOutputData? =
        when {
            output == null || output == EngineErrorData -> output
            sourceTypeExpr is TypeExpr.List && bridgeTypeExpr is TypeExpr.List -> {
                require(output is List<*>) {
                    "Node-list field resolver did not return a list"
                }
                output.map { value ->
                    lowerNodeReferences(
                        output = value,
                        sourceTypeExpr = sourceTypeExpr.elementType,
                        bridgeTypeExpr = bridgeTypeExpr.elementType,
                    )
                }
            }
            sourceTypeExpr is TypeExpr.Named && bridgeTypeExpr is TypeExpr.Named -> {
                require(output is EngineObjectData.Sync) {
                    "Node field resolver did not return a node reference"
                }
                val outputType = output.schemaType
                val idField = objectField(outputType.typeName, "id")
                val id = output.get(idField.fieldName)
                require(id != EngineErrorData && id is String) {
                    "Node reference ${outputType.typeName}/id must contain a non-error ID"
                }
                val bridgeType = bridgeTypeExpr.baseType as Schema.ObjectType
                val bridgeId = objectField(bridgeType.typeName, NODE_BRIDGE_ID_FIELD)
                engineObjectDataOf(
                    schemaType = bridgeType,
                    fields =
                        mapOf(
                            bridgeId.fieldName to
                                "$TYPED_NODE_ID_PREFIX${outputType.typeName.length}:" +
                                    "${outputType.typeName}$id",
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
            return GJSchema(
                graphQLSchema = graphQLSchema,
                decodedSchema = GJSchemaDecoder(graphQLSchema).decode(),
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
                .also(::validateNodeFieldCovariance)
        }

        private fun validateNodeFieldCovariance(schema: GraphQLSchema) {
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
                            implementsInterface(schema, interfaceType.name, objectType)
                        }.forEach { interfaceType ->
                            interfaceType.fieldDefinitions.forEach { interfaceField ->
                                val interfaceOutput =
                                    interfaceField.nodeOutputName(schema, node) ?: return@forEach
                                val objectOutput =
                                    objectType
                                        .getFieldDefinition(interfaceField.name)
                                        ?.nodeOutputName(schema, node)
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
            schema: GraphQLSchema,
            node: GraphQLInterfaceType,
        ): String? {
            val output = GraphQLTypeUtil.unwrapAll(type) as? GraphQLImplementingType ?: return null
            return output.name.takeIf {
                output.name == node.name || implementsInterface(schema, node.name, output)
            }
        }

        private fun validateReservedNames(schemaSDL: String) {
            val invalidNamesByToken =
                linkedMapOf(
                    NODE_SYNTHETIC_NAME_TOKEN to linkedSetOf<String>(),
                    TYPENAME_SYNTHETIC_NAME_TOKEN to linkedSetOf(),
                )

            fun visit(node: Node<*>) {
                val name = (node as? NamedNode<*>)?.name
                if (name != null) {
                    invalidNamesByToken.forEach { (token, invalidNames) ->
                        if (name.contains(token)) {
                            invalidNames.add(name)
                        }
                    }
                }
                node.children.forEach(::visit)
            }

            Parser.parse(schemaSDL).children.forEach(::visit)
            val collisions =
                invalidNamesByToken.filterValues { invalidNames -> invalidNames.isNotEmpty() }
            require(collisions.isEmpty()) {
                collisions.entries.joinToString(
                    separator = "; ",
                ) { (token, invalidNames) ->
                    "Source schema names cannot contain reserved token $token: " +
                        invalidNames.sorted().joinToString()
                }
            }
        }
    }
}
