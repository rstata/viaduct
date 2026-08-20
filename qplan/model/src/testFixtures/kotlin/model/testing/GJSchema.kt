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
import model.EngineObjectDataEntry
import model.EngineOutputData
import model.TypeExpr
import model.engineObjectDataOf
import model.qplanSchemaTypeOrNull
import model.requireField
import model.requireObjectField
import model.requireType
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
        val sourceTypeExpr = sourceTypeExpr(field)
        return if (isLoweredNodeField(field)) {
            lowerNodeReferences(
                output = output,
                sourceTypeExpr = sourceTypeExpr,
                bridgeTypeExpr = field.type,
            )
        } else {
            lowerOrdinaryOutput(
                output = output,
                sourceTypeExpr = sourceTypeExpr,
                loweredTypeExpr = field.type,
            )
        }
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
                val outputType =
                    requireType(output.type.name) as? Schema.Object
                        ?: throw IllegalArgumentException(
                            "Node field resolver returned unknown object type ${output.type.name}",
                        )
                val idField = requireObjectField(outputType.name, "id")
                val id = output.get(idField.name)
                require(id != EngineErrorData && id is String) {
                    "Node reference ${outputType.name}/id must contain a non-error ID"
                }
                val declaredBridgeType =
                    bridgeTypeExpr.baseTypeDef as Schema.CompositeTypeDef
                val bridgeType =
                    requireType(nodeBridgeTypeName(outputType)) as Schema.Object
                require(bridgeType in declaredBridgeType.possibleObjectTypes) {
                    "Node reference ${outputType.name} is not valid for " +
                        sourceTypeExpr.baseTypeDef.name
                }
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

    private fun lowerOrdinaryOutput(
        output: EngineOutputData?,
        sourceTypeExpr: TypeExpr<Schema.OutputTypeDef>,
        loweredTypeExpr: TypeExpr<Schema.OutputTypeDef>,
    ): EngineOutputData? =
        when {
            output == null || output == EngineErrorData -> output
            sourceTypeExpr.isList && loweredTypeExpr.isList -> {
                require(output is List<*>) {
                    "Source output for $sourceTypeExpr is not a list"
                }
                val sourceElementType = checkNotNull(sourceTypeExpr.unwrapList())
                val loweredElementType = checkNotNull(loweredTypeExpr.unwrapList())
                output.map { value ->
                    lowerOrdinaryOutput(
                        output = value,
                        sourceTypeExpr = sourceElementType,
                        loweredTypeExpr = loweredElementType,
                    )
                }
            }
            !sourceTypeExpr.isList && !loweredTypeExpr.isList -> {
                val sourceType = sourceTypeExpr.baseTypeDef
                if (sourceType !is Schema.CompositeTypeDef) {
                    output
                } else {
                    require(output is EngineObjectData.Sync) {
                        "Source output for ${sourceType.name} is not an object"
                    }
                    lowerOrdinaryObject(output, loweredTypeExpr)
                }
            }
            else -> error("Source and lowered type expressions have different list shapes")
        }

    private fun lowerOrdinaryObject(
        output: EngineObjectData.Sync,
        loweredTypeExpr: TypeExpr<Schema.OutputTypeDef>,
    ): EngineObjectData.Sync {
        output.qplanSchemaTypeOrNull?.let { return output }

        val outputType =
            requireType(output.type.name) as? Schema.Object
                ?: throw IllegalArgumentException(
                    "Source resolver returned unknown object type ${output.type.name}",
                )
        val declaredType = loweredTypeExpr.baseTypeDef as Schema.CompositeTypeDef
        require(outputType in declaredType.possibleObjectTypes) {
            "Source object ${outputType.name} is not valid for ${declaredType.name}"
        }
        val sourceObject =
            graphQLSchema.getObjectType(outputType.name)
                ?: throw IllegalArgumentException(
                    "${outputType.name} is not a source GraphQL object type",
                )
        val fields =
            output.getSelections().map { selection ->
                requireNotNull(sourceObject.getFieldDefinition(selection)) {
                    "Source object ${outputType.name} has no field named $selection"
                }
                val loweredField = fieldFromSource(outputType.name, selection)
                require(loweredField is Schema.ObjectField) {
                    "${outputType.name}/$selection does not lower to an object field"
                }
                require(loweredField.args.isEmpty()) {
                    "Passive object field ${outputType.name}/$selection must be argumentless"
                }
                EngineObjectDataEntry.of(
                    selection = loweredField.name,
                    field = loweredField,
                    value = lowerSourceOutput(loweredField, output.get(selection)),
                )
            }
        return engineObjectDataOf(outputType, fields)
    }

    companion object {
        private val STANDARD_SCALAR_NAMES = setOf("Int", "Float", "String", "Boolean", "ID")
        private val STANDARD_DIRECTIVE_NAMES =
            setOf("skip", "include", "deprecated", "specifiedBy", "oneOf")

        @JvmStatic
        fun fromSDL(schemaSDL: String): GJSchema {
            val graphQLSchema = parseSchema(schemaSDL)
            val typeRelations = GraphQLTypeRelations(graphQLSchema)
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
