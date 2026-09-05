package model.testing

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult
import graphql.language.NamedNode
import graphql.language.Node
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import model.EngineErrorData
import model.EngineObjectDataEntry
import model.EngineOutputData
import model.engineObjectDataOf
import model.lowering.LOWERING_SYNTHETIC_NAME_TOKEN
import model.lowering.NODE_BRIDGE_ID_FIELD
import model.lowering.TYPED_NODE_ID_PREFIX
import model.lowering.VIADUCT_IGNORE_SYMBOL
import model.lowering.isLoweredNodeBridgeField
import model.lowering.lowerSchema
import model.lowering.loweredFieldFromSourceCoordinate
import model.lowering.nodeBridgeTypeName
import model.lowering.sourceTypeExpr
import model.outputType
import model.qplanSchemaTypeOrNull
import model.requireField
import model.requireObjectField
import model.requireType
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.graphqljava.toGraphQLSchema
import viaduct.graphql.schema.graphqljava.viaductSchema
import viaduct.graphql.utils.GraphQLTypeRelations

/**
 * A fixture pair of the GraphQL-visible source schema and the canonical decoded [ViaductSchema].
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
    internal val loweredSchema: ViaductSchema,
) : ViaductSchema by loweredSchema {
    internal fun sourceCompositeType(type: ViaductSchema.CompositeTypeDef): GraphQLCompositeType {
        require(requireType(type.name) == type) {
            "${type.name} is not canonical in this schema"
        }
        return graphQLSchema.getType(type.name) as? GraphQLCompositeType
            ?: throw IllegalArgumentException("${type.name} is not a source composite type")
    }

    /** The canonical concrete object types available to fixture registry lowering. */
    internal val objectTypes: List<ViaductSchema.Object>
        get() =
            graphQLSchema.allTypesAsList
                .filterIsInstance<GraphQLObjectType>()
                .filterNot { it.name.startsWith("__") }
                .map { requireType(it.name) as ViaductSchema.Object }

    internal fun fieldFromSource(
        typeName: String,
        fieldName: String,
    ): ViaductSchema.Field =
        loweredSchema.loweredFieldFromSourceCoordinate(typeName, fieldName)

    internal fun sourceTypeExpr(
        field: ViaductSchema.Field,
    ): ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> =
        loweredSchema.sourceTypeExpr(field)

    internal fun isLoweredNodeField(field: ViaductSchema.Field): Boolean =
        field.isLoweredNodeBridgeField()

    internal fun lowerSourceOutput(
        field: ViaductSchema.Field,
        output: EngineOutputData?,
    ): EngineOutputData? {
        val sourceTypeExpr = sourceTypeExpr(field)
        return if (isLoweredNodeField(field)) {
            lowerNodeReferences(
                output = output,
                sourceTypeExpr = sourceTypeExpr,
                bridgeTypeExpr = field.outputType,
            )
        } else {
            lowerOrdinaryOutput(
                output = output,
                sourceTypeExpr = sourceTypeExpr,
                loweredTypeExpr = field.outputType,
            )
        }
    }

    private fun lowerNodeReferences(
        output: EngineOutputData?,
        sourceTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        bridgeTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    ): EngineOutputData? =
        when {
            output == null || output is EngineErrorData -> output
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
                    requireType(output.type.name) as? ViaductSchema.Object
                        ?: throw IllegalArgumentException(
                            "Node field resolver returned unknown object type ${output.type.name}",
                        )
                val idField = requireObjectField(outputType.name, "id")
                val id = output.get(idField.name)
                require(id !is EngineErrorData && id is String) {
                    "Node reference ${outputType.name}/id must contain a non-error ID"
                }
                val declaredBridgeType =
                    bridgeTypeExpr.baseTypeDef as ViaductSchema.CompositeTypeDef
                val bridgeType =
                    requireType(nodeBridgeTypeName(outputType.name)) as ViaductSchema.Object
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
        sourceTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        loweredTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    ): EngineOutputData? =
        when {
            output == null || output is EngineErrorData -> output
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
                if (sourceType !is ViaductSchema.CompositeTypeDef) {
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
        loweredTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    ): EngineObjectData.Sync {
        output.qplanSchemaTypeOrNull?.let { return output }

        val outputType =
            requireType(output.type.name) as? ViaductSchema.Object
                ?: throw IllegalArgumentException(
                    "Source resolver returned unknown object type ${output.type.name}",
                )
        val declaredType = loweredTypeExpr.baseTypeDef as ViaductSchema.CompositeTypeDef
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
                require(loweredField is ViaductSchema.ObjectField) {
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
        private val SCALARS_REQUIRING_REGISTRATION = setOf("Int", "Float", "ID")
        private val STANDARD_DIRECTIVE_NAMES =
            setOf("skip", "include", "deprecated", "specifiedBy", "oneOf", "parent")

        @JvmStatic
        fun fromSDL(schemaSDL: String): GJSchema {
            val graphQLSchema = parseSchema(schemaSDL)
            require(graphQLSchema.mutationType == null) {
                "Mutation roots are outside the model"
            }
            require(graphQLSchema.subscriptionType == null) {
                "Subscription roots are outside the model"
            }
            require(graphQLSchema.queryType.name == "Query") {
                "The model requires the query root to be named Query"
            }
            val typeRelations = GraphQLTypeRelations(graphQLSchema)
            val sourceSchema = graphQLSchema.viaductSchema()
            val builtLowered = lowerSchema(sourceSchema)
            val scalarsNeeded =
                SCALARS_REQUIRING_REGISTRATION.filterTo(mutableSetOf()) {
                    graphQLSchema.getType(it) != null
                }
            builtLowered.toGraphQLSchema(scalarsNeeded = scalarsNeeded)
            return GJSchema(
                graphQLSchema = graphQLSchema,
                typeRelations = typeRelations,
                loweredSchema = builtLowered,
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
