package model.testing

import model.ObjectEngineResult

import graphql.language.NamedNode
import graphql.language.Node
import graphql.parser.Parser
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import model.Schema
import model.TypeExpr
import model.Value

/**
 * A fixture pair of the GraphQL-visible source schema and the canonical decoded [Schema].
 *
 * Construct the reasoning world's one schema before its values and assumptions so every non-error
 * value is created through this exact canonical graph. The canonical graph may contain synthetic
 * node bridge types and fields absent from the retained GraphQL Java schema. [Value.Error] is
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
                .filterIsInstance<graphql.schema.GraphQLObjectType>()
                .filterNot { it.name.startsWith("__") }
                .map { type(it.name) as Schema.ObjectType }

    internal fun fieldFromSource(
        typeName: String,
        fieldName: String,
    ): Schema.OutputField {
        val sourceField =
            (graphQLSchema.getType(typeName) as? GraphQLFieldsContainer)
                ?.getFieldDefinition(fieldName)
                ?: return field(typeName, fieldName).also {
                    require(fieldName == "__typename") {
                        "$typeName/$fieldName is not a source GraphQL field"
                    }
                }
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
        output: Value.Output?,
    ): Value.Output? {
        if (!isLoweredNodeField(field)) return output
        return lowerNodeReferences(
            output = output,
            sourceTypeExpr = sourceTypeExpr(field),
            bridgeTypeExpr = field.typeExpr,
        )
    }

    private fun sourceField(
        field: Schema.OutputField,
    ): graphql.schema.GraphQLFieldDefinition? {
        val sourceContainer =
            graphQLSchema.getType(field.containingType.typeName) as? GraphQLFieldsContainer
                ?: return null
        sourceContainer.getFieldDefinition(field.fieldName)?.let { return it }
        if (!field.fieldName.endsWith(NODE_BRIDGE_FIELD_SUFFIX)) return null
        return sourceContainer
            .getFieldDefinition(field.fieldName.removeSuffix(NODE_BRIDGE_FIELD_SUFFIX))
            ?.takeIf { it.isNodeValued() }
    }

    private fun graphql.schema.GraphQLFieldDefinition.isNodeValued(): Boolean {
        val node = graphQLSchema.getType("Node") as? GraphQLInterfaceType ?: return false
        val output = GraphQLTypeUtil.unwrapAll(type) as? GraphQLImplementingType ?: return false
        return output.name == node.name ||
            implementsInterface(graphQLSchema, node.name, output)
    }

    private fun lowerNodeReferences(
        output: Value.Output?,
        sourceTypeExpr: TypeExpr<Schema.OutputType>,
        bridgeTypeExpr: TypeExpr<Schema.OutputType>,
    ): Value.Output? =
        when {
            output == null || output == Value.Error -> output
            sourceTypeExpr is TypeExpr.List && bridgeTypeExpr is TypeExpr.List -> {
                require(output is Value.OutputList) {
                    "Node-list field resolver did not return a list"
                }
                Value.OutputList.of(
                    typeExpr = bridgeTypeExpr.elementType,
                    values =
                        output.values.map { value ->
                            lowerNodeReferences(
                                output = value,
                                sourceTypeExpr = sourceTypeExpr.elementType,
                                bridgeTypeExpr = bridgeTypeExpr.elementType,
                            )
                        },
                )
            }
            sourceTypeExpr is TypeExpr.Named && bridgeTypeExpr is TypeExpr.Named -> {
                require(output is Value.Object) {
                    "Node field resolver did not return a node reference"
                }
                val idField = objectField(output.type.typeName, "id")
                val id =
                    output.fieldValues.getValue(
                        ObjectEngineResult.GroundKey.of(idField, emptyMap()),
                    )
                require(id != Value.Error && id is Value.ID) {
                    "Node reference ${output.type.typeName}/id must contain a non-error ID"
                }
                val bridgeType = bridgeTypeExpr.baseType as Schema.ObjectType
                val bridgeId = objectField(bridgeType.typeName, NODE_BRIDGE_ID_FIELD)
                Value.Object.of(
                    type = bridgeType,
                    fields =
                        mapOf(
                            ObjectEngineResult.GroundKey.of(bridgeId, emptyMap()) to
                                Value.ID.of(
                                    "$TYPED_NODE_ID_PREFIX${output.type.typeName.length}:" +
                                        "${output.type.typeName}${id.idValue}",
                                ),
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

        private fun graphql.schema.GraphQLFieldDefinition.nodeOutputName(
            schema: GraphQLSchema,
            node: GraphQLInterfaceType,
        ): String? {
            val output = GraphQLTypeUtil.unwrapAll(type) as? GraphQLImplementingType ?: return null
            return output.name.takeIf {
                output.name == node.name || implementsInterface(schema, node.name, output)
            }
        }

        private fun validateReservedNames(schemaSDL: String) {
            val invalidNames = linkedSetOf<String>()

            fun visit(node: Node<*>) {
                val name = (node as? NamedNode<*>)?.name
                if (name != null && name.contains(SYNTHETIC_NAME_TOKEN)) {
                    invalidNames.add(name)
                }
                node.children.forEach(::visit)
            }

            Parser.parse(schemaSDL).children.forEach(::visit)
            require(invalidNames.isEmpty()) {
                "Source schema names cannot contain reserved token $SYNTHETIC_NAME_TOKEN: " +
                    invalidNames.sorted().joinToString()
            }
        }
    }
}
