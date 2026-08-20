package model.testing

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value as GraphQLValue
import graphql.language.VariableReference
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.InputValueWithState
import model.ArgumentResolutionError
import model.Arguments
import model.CoercedDefaultValue
import model.EngineInputData
import model.EngineInputListData
import model.EngineInputObjectData
import model.EngineSimpleData
import model.Schema
import model.TypeExpr
import model.coerceArgumentExpression
import model.requireType
import viaduct.graphql.utils.GraphQLTypeRelation
import viaduct.graphql.utils.GraphQLTypeRelations

/** Names reserved for fixture-generated canonical types and fields. */
internal const val LOWERING_SYNTHETIC_NAME_TOKEN = "V_A"
internal const val ALL_SOURCE_OBJECTS_TYPE = "V_A_AllSourceObjects"
internal const val LOWERED_TYPENAME_FIELD = "V_A_typename"
internal const val VIADUCT_IGNORE_SYMBOL = "VIADUCT_IGNORE"
internal const val NODE_BRIDGE_TYPE_SUFFIX = "_V_A_Bridge"
internal const val NODE_BRIDGE_FIELD_SUFFIX = "_V_A_node"
internal const val NODE_BRIDGE_ID_FIELD = "id"
internal const val NODE_BRIDGE_PAYLOAD_FIELD = "node"
internal const val TYPED_NODE_ID_PREFIX = "\$node:"

/** Fixture-only variable binding that denotes failure outside the engine-input value domain. */
internal data object ErroneousVariableValue

internal fun nodeBridgeTypeName(nodeType: Schema.CompositeTypeDef): String =
    nodeType.name + NODE_BRIDGE_TYPE_SUFFIX

internal fun nodeBridgeFieldName(sourceFieldName: String): String =
    sourceFieldName + NODE_BRIDGE_FIELD_SUFFIX

/**
 * Decodes external GraphQL SDL into the canonical fixture schema.
 *
 * For every Node object or interface `T`, the decoded schema contains a matching
 * `T_V_A_Bridge` object or interface with `id: ID` and `node: T`. Bridge possible-object
 * relationships mirror the source Node hierarchy. A source field `foo: W<T>` is omitted and
 * represented by `foo_V_A_node: W<T_V_A_Bridge>`, preserving its arguments, list structure, and
 * nullability. These definitions belong only to the lowered reasoning world and are never parsed
 * from GraphQL text.
 */
internal class GJSchemaDecoder(
    private val graphQLSchema: GraphQLSchema,
    private val typeRelations: GraphQLTypeRelations,
) {
    private val types = linkedMapOf<String, Schema.TypeDef>()
    private val abstractCompositeFields =
        linkedMapOf<Schema.CompositeTypeDef, MutableMap<String, Schema.Field>>()
    private val objectFields =
        linkedMapOf<Schema.Object, MutableMap<String, Schema.ObjectField>>()
    private val nodeBridgeTypes = linkedSetOf<Schema.CompositeTypeDef>()
    private val inputFields =
        linkedMapOf<Schema.Input, MutableMap<String, Schema.InputField>>()
    private val possibleTypeSets =
        linkedMapOf<Schema.CompositeTypeDef, MutableSet<Schema.Object>>()
    private lateinit var schema: Schema
    private val noVariableValues: Map<String, EngineInputData?> = emptyMap()

    fun decode(): Schema {
        require(graphQLSchema.mutationType == null) {
            "Mutation roots are outside the model"
        }
        require(graphQLSchema.subscriptionType == null) {
            "Subscription roots are outside the model"
        }
        require(graphQLSchema.queryType.name == "Query") {
            "The model requires the query root to be named Query"
        }

        registerBuiltInScalars()
        createTypeShells()
        createAllSourceObjectsTypeShell()
        createNodeBridgeTypeShells()

        val query = types["Query"] as Schema.Object
        schema =
            DecodedSchema(
                queryTypeDef = query,
                types = types.toMap(),
            )
        populatePossibleTypes()
        populateNodeBridgePossibleTypes()
        populateAllSourceObjectsPossibleTypes()
        populateInputFields()
        populateCompositeFields()
        populateNodeBridgeTypes()
        populateGraphQLJavaObjectDefinitions()

        return schema
    }

    private fun registerBuiltInScalars() {
        listOf(
            Schema.IntType,
            Schema.FloatType,
            Schema.StringType,
            Schema.BooleanType,
            Schema.IDType,
        ).forEach { scalar ->
            types[scalar.name] = scalar
        }
    }

    private fun createTypeShells() {
        graphQLSchema.allTypesAsList
            .filterNot { it.name.startsWith("__") }
            .forEach { graphQLType ->
                when (graphQLType) {
                    is GraphQLScalarType -> types[graphQLType.name]!!

                    is GraphQLEnumType ->
                        types[graphQLType.name] =
                            EnumTypeImpl(
                                name = graphQLType.name,
                                valueNames = graphQLType.values.mapTo(linkedSetOf()) { it.name },
                            )

                    is GraphQLInputObjectType -> {
                        val type = InputObjectTypeImpl(graphQLType.name)
                        types[type.name] = type
                        inputFields[type] = linkedMapOf()
                    }

                    is GraphQLObjectType -> {
                        val possibleTypes = linkedSetOf<Schema.Object>()
                        val type = ObjectTypeImpl(graphQLType.name, possibleTypes)
                        possibleTypes += type
                        types[type.name] = type
                        objectFields[type] = linkedMapOf()
                        possibleTypeSets[type] = possibleTypes
                    }

                    is GraphQLInterfaceType, is GraphQLUnionType -> {
                        val possibleTypes = linkedSetOf<Schema.Object>()
                        val type: Schema.CompositeTypeDef =
                            when (graphQLType) {
                            is GraphQLInterfaceType ->
                                InterfaceTypeImpl(graphQLType.name, possibleTypes)
                            else ->
                                UnionTypeImpl((graphQLType as GraphQLUnionType).name, possibleTypes)
                            }
                        registerComposite(type, possibleTypes)
                    }

                    else -> error("Unexpected GraphQL type: $graphQLType")
                }
            }
    }

    private fun createNodeBridgeTypeShells() {
        sourceNodeTypes().forEach { nodeType ->
            val bridgeTypeName = nodeType.name + NODE_BRIDGE_TYPE_SUFFIX
            require(bridgeTypeName !in types) {
                "Synthetic node bridge type collides with $bridgeTypeName"
            }
            val possibleTypes = linkedSetOf<Schema.Object>()
            val bridgeType: Schema.CompositeTypeDef =
                when (nodeType) {
                    is GraphQLObjectType -> {
                        val type = ObjectTypeImpl(bridgeTypeName, possibleTypes)
                        types[bridgeTypeName] = type
                        objectFields[type] = linkedMapOf()
                        possibleTypeSets[type] = possibleTypes
                        type
                    }
                    is GraphQLInterfaceType -> {
                        val type = InterfaceTypeImpl(bridgeTypeName, possibleTypes)
                        registerComposite(type, possibleTypes)
                        type
                    }
                    else -> error("Unexpected Node subtype: $nodeType")
                }
            nodeBridgeTypes += bridgeType
        }
    }

    private fun createAllSourceObjectsTypeShell() {
        require(ALL_SOURCE_OBJECTS_TYPE !in types) {
            "Synthetic typename interface collides with $ALL_SOURCE_OBJECTS_TYPE"
        }
        val possibleTypes = linkedSetOf<Schema.Object>()
        registerComposite(
            InterfaceTypeImpl(ALL_SOURCE_OBJECTS_TYPE, possibleTypes),
            possibleTypes,
        )
    }

    private fun sourceNodeTypes(): List<GraphQLImplementingType> {
        val node = graphQLSchema.getType("Node") as? GraphQLInterfaceType ?: return emptyList()
        return graphQLSchema.allTypesAsList
            .filterIsInstance<GraphQLImplementingType>()
            .filter { outputType ->
                typeRelations.relationUnwrapped(node, outputType) in
                    setOf(GraphQLTypeRelation.Same, GraphQLTypeRelation.WiderThan)
            }
    }

    private fun registerComposite(
        type: Schema.CompositeTypeDef,
        possibleTypes: MutableSet<Schema.Object>,
    ) {
        types[type.name] = type
        abstractCompositeFields[type] = linkedMapOf()
        possibleTypeSets[type] = possibleTypes
    }

    private fun populatePossibleTypes() {
        graphQLSchema.allTypesAsList
            .filterIsInstance<GraphQLCompositeType>()
            .filterNot { it.name.startsWith("__") }
            .forEach { graphQLType ->
                val modelType = types.getValue(graphQLType.name) as Schema.CompositeTypeDef
                typeRelations
                    .possibleObjectTypes(graphQLType)
                    .mapTo(possibleTypeSets.getValue(modelType)) {
                        types.getValue(it.name) as Schema.Object
                    }
            }
    }

    private fun populateNodeBridgePossibleTypes() {
        sourceNodeTypes().forEach { sourceType ->
            val bridgeType =
                types.getValue(sourceType.name + NODE_BRIDGE_TYPE_SUFFIX)
                    as Schema.CompositeTypeDef
            typeRelations
                .possibleObjectTypes(sourceType)
                .mapTo(possibleTypeSets.getValue(bridgeType)) { sourceObject ->
                    types.getValue(sourceObject.name + NODE_BRIDGE_TYPE_SUFFIX) as Schema.Object
                }
        }
    }

    private fun populateAllSourceObjectsPossibleTypes() {
        val allSourceObjects = types.getValue(ALL_SOURCE_OBJECTS_TYPE) as Schema.Interface
        types.values
            .filterIsInstance<Schema.Object>()
            .filterNot(nodeBridgeTypes::contains)
            .toCollection(possibleTypeSets.getValue(allSourceObjects))
    }

    private fun populateInputFields() {
        graphQLSchema.allTypesAsList
            .filterIsInstance<GraphQLInputObjectType>()
            .forEach { graphQLType ->
                val modelType = types.getValue(graphQLType.name) as Schema.Input
                val modelFields = inputFields.getValue(modelType)
                graphQLType.fieldDefinitions.forEach { graphQLField ->
                    val field =
                        InputFieldImpl(
                            name = graphQLField.name,
                            containingDef = modelType,
                            type = decodeInputType(graphQLField.type),
                            defaultValue =
                                decodeDefault(
                                    graphQLField.type,
                                    graphQLField.inputFieldDefaultValue,
                                ),
                        )
                    modelFields[graphQLField.name] = field
                    (modelType as InputObjectTypeImpl).add(field)
                }
            }
    }

    private fun populateCompositeFields() {
        types.values
            .filter { it is Schema.Object || it is Schema.Interface }
            .map { it as Schema.CompositeTypeDef }
            .filterNot(nodeBridgeTypes::contains)
            .forEach(::addLoweredTypenameField)

        graphQLSchema.allTypesAsList
            .filterIsInstance<GraphQLFieldsContainer>()
            .filter { it is GraphQLObjectType || it is GraphQLInterfaceType }
            .filterNot { it.name.startsWith("__") }
            .forEach { graphQLType ->
                val modelType = types.getValue(graphQLType.name) as Schema.CompositeTypeDef
                graphQLType.fieldDefinitions.forEach { graphQLField ->
                    val sourceTypeExpr = decodeOutputType(graphQLField.type)
                    val modelField =
                        outputFieldOf(
                            name =
                                if (isNodeType(sourceTypeExpr.baseTypeDef)) {
                                    nodeBridgeFieldName(graphQLField.name)
                                } else {
                                    graphQLField.name
                                },
                            containingDef = modelType,
                            type =
                                if (isNodeType(sourceTypeExpr.baseTypeDef)) {
                                    nodeBridgeTypeExpr(sourceTypeExpr)
                                } else {
                                    sourceTypeExpr
                                },
                        ) { containingField ->
                            fieldArgsOf(
                                definitions = graphQLField.arguments,
                                containingDef = containingField,
                                name = { it.name },
                            ) { graphQLArgument ->
                                FieldArgumentImpl(
                                    name = graphQLArgument.name,
                                    containingDef = containingField,
                                    type = decodeInputType(graphQLArgument.type),
                                    defaultValue =
                                        decodeDefault(
                                            graphQLArgument.type,
                                            graphQLArgument.argumentDefaultValue,
                                        ),
                                )
                            }
                        }
                    addCompositeField(modelType, modelField)
                }
            }
    }

    private fun addLoweredTypenameField(type: Schema.CompositeTypeDef) {
        val field =
            outputFieldOf(
                name = LOWERED_TYPENAME_FIELD,
                containingDef = type,
                type = TypeExpr.Named.of(Schema.StringType, isNullable = false),
            )
        addCompositeField(type, field)
    }

    private fun populateNodeBridgeTypes() {
        sourceNodeTypes().forEach { sourceType ->
            val nodeType = types.getValue(sourceType.name) as Schema.CompositeTypeDef
            val bridgeType =
                types.getValue(sourceType.name + NODE_BRIDGE_TYPE_SUFFIX)
                    as Schema.CompositeTypeDef
            addCompositeField(
                bridgeType,
                outputFieldOf(
                    name = NODE_BRIDGE_ID_FIELD,
                    containingDef = bridgeType,
                    type = TypeExpr.Named.of(Schema.IDType),
                ),
            )
            addCompositeField(
                bridgeType,
                outputFieldOf(
                    name = NODE_BRIDGE_PAYLOAD_FIELD,
                    containingDef = bridgeType,
                    type = TypeExpr.Named.of(nodeType),
                ),
            )
        }
    }

    private fun populateGraphQLJavaObjectDefinitions() {
        objectFields.keys.forEach { objectType ->
            val sourceDefinition = graphQLSchema.getObjectType(objectType.name)
            val modeledFieldNames = objectType.fields.mapTo(linkedSetOf(), Schema.Field::name)
            val definition =
                sourceDefinition
                    ?.takeIf { source ->
                        source.fieldDefinitions.mapTo(linkedSetOf()) { it.name } ==
                            modeledFieldNames
                    }
                    ?: graphQLJavaDefinitionOf(objectType)
            (objectType as ObjectTypeImpl).initializeGraphQLJavaDefinition(definition)
        }
    }

    private fun graphQLJavaDefinitionOf(
        objectType: Schema.Object,
    ): GraphQLObjectType =
        GraphQLObjectType
            .newObject()
            .name(objectType.name)
            .fields(
                objectType.fields
                    .map { field ->
                        GraphQLFieldDefinition
                            .newFieldDefinition()
                            .name(field.name)
                            .type(field.type.toGraphQLType() as GraphQLOutputType)
                            .arguments(
                                field.args.map { argument ->
                                    GraphQLArgument
                                        .newArgument()
                                        .name(argument.name)
                                        .type(
                                            argument.type.toGraphQLType() as GraphQLInputType,
                                        ).build()
                                },
                            ).build()
                    },
            ).build()

    private fun TypeExpr<Schema.TypeDef>.toGraphQLType(): GraphQLType {
        val elementType = unwrapList()
        val nullableType: GraphQLType =
            if (elementType == null) {
                GraphQLTypeReference.typeRef(baseTypeDef.name)
            } else {
                GraphQLList(elementType.toGraphQLType())
            }
        return if (isNullable) {
            nullableType
        } else {
            GraphQLNonNull(nullableType)
        }
    }

    private fun isNodeType(type: Schema.OutputTypeDef): Boolean {
        val nodeType = graphQLSchema.getType("Node") as? GraphQLInterfaceType ?: return false
        if (type !is Schema.CompositeTypeDef) return false
        val sourceType = graphQLSchema.getType(type.name) as GraphQLCompositeType
        return typeRelations.relationUnwrapped(nodeType, sourceType) in
            setOf(GraphQLTypeRelation.Same, GraphQLTypeRelation.WiderThan)
    }

    private fun addCompositeField(
        type: Schema.CompositeTypeDef,
        field: Schema.Field,
    ) {
        when (type) {
            is Schema.Object -> {
                val objectField = field as Schema.ObjectField
                objectFields.getValue(type)[field.name] = objectField
                (type as ObjectTypeImpl).add(objectField)
            }
            is Schema.Interface -> {
                abstractCompositeFields.getValue(type)[field.name] = field
                (type as InterfaceTypeImpl).add(field)
            }
            is Schema.Union -> {
                abstractCompositeFields.getValue(type)[field.name] = field
                (type as UnionTypeImpl).add(field)
            }
        }
    }

    private fun nodeBridgeTypeExpr(
        typeExpr: TypeExpr<Schema.OutputTypeDef>,
    ): TypeExpr<Schema.OutputTypeDef> {
        val elementType = typeExpr.unwrapList()
        return if (elementType == null) {
            TypeExpr.Named.of(
                baseType =
                    types.getValue(
                        (typeExpr.baseTypeDef as Schema.CompositeTypeDef).name +
                            NODE_BRIDGE_TYPE_SUFFIX,
                    ) as Schema.CompositeTypeDef,
                isNullable = typeExpr.isNullable,
            )
        } else {
            TypeExpr.List.of(
                elementType = nodeBridgeTypeExpr(elementType),
                isNullable = typeExpr.isNullable,
            )
        }
    }

    private fun decodeOutputType(type: GraphQLOutputType): TypeExpr<Schema.OutputTypeDef> =
        decodeModelOutputType(type, schema)

    private fun decodeInputType(type: GraphQLInputType): TypeExpr<Schema.InputTypeDef> =
        decodeType(type) { typeName ->
            types.getValue(typeName) as Schema.InputTypeDef
        }

    private fun <T : Schema.TypeDef> decodeType(
        type: GraphQLType,
        resolveNamedType: (String) -> T,
    ): TypeExpr<T> =
        when (type) {
            is GraphQLNonNull ->
                decodeNonNullType(type.wrappedType, resolveNamedType)

            is GraphQLList ->
                TypeExpr.List.of(
                    elementType = decodeType(type.wrappedType, resolveNamedType),
                    isNullable = true,
                )

            is GraphQLNamedType ->
                TypeExpr.Named.of(
                    baseType = resolveNamedType(type.name),
                    isNullable = true,
                )

            else -> error("Unexpected GraphQL type expression: $type")
        }

    private fun <T : Schema.TypeDef> decodeNonNullType(
        type: GraphQLType,
        resolveNamedType: (String) -> T,
    ): TypeExpr<T> =
        when (type) {
            is GraphQLList ->
                TypeExpr.List.of(
                    elementType = decodeType(type.wrappedType, resolveNamedType),
                    isNullable = false,
                )

            is GraphQLNamedType ->
                TypeExpr.Named.of(
                    baseType = resolveNamedType(type.name),
                    isNullable = false,
                )

            else -> error("Unexpected non-null GraphQL type expression: $type")
        }

    private fun decodeDefault(
        type: GraphQLInputType,
        value: InputValueWithState,
    ): CoercedDefaultValue =
        if (value.isNotSet) {
            CoercedDefaultValue.Absent
        } else {
            val decoded =
                decodeInputValue(
                    type,
                    value,
                    noVariableValues,
                    schema,
                )
            check(decoded != ArgumentResolutionError && decoded !is Arguments.Variable)
            CoercedDefaultValue.of(decoded)
        }

}

internal fun decodeInputValue(
    type: GraphQLInputType,
    value: InputValueWithState,
    variableValues: Map<String, EngineInputData?>,
    schema: Schema,
    variableField: Schema.ObjectField? = null,
): Any? =
    if (value.isLiteral) {
        decodeLiteral(
            type,
            value.value as GraphQLValue<*>,
            variableValues,
            schema,
            variableField,
        )
    } else {
        coerceArgumentExpression(
            decodeModelInputType(type, schema),
            decodeExternal(type, value.value, variableValues, schema),
        )
    }

internal fun decodeLiteral(
    type: GraphQLInputType,
    value: GraphQLValue<*>,
    variableValues: Map<String, EngineInputData?>,
    schema: Schema,
    variableField: Schema.ObjectField? = null,
): Any? {
    if (value is VariableReference) {
        return if (variableValues.containsKey(value.name)) {
            val bound = variableValues.getValue(value.name)
            if (bound === ErroneousVariableValue) {
                ArgumentResolutionError
            } else {
                coerceArgumentExpression(
                    decodeModelInputType(type, schema),
                    bound,
                )
            }
        } else {
            requireNotNull(variableField) {
                "Unbound operation variable \$${value.name}"
            }
            Arguments.Variable.of(variableField, value.name)
        }
    }
    if (value is NullValue) {
        return null
    }

    return when (type) {
        is GraphQLNonNull ->
            decodeLiteral(
                type.wrappedType as GraphQLInputType,
                value,
                variableValues,
                schema,
                variableField,
            )

        is GraphQLList -> {
            val values = if (value is ArrayValue) value.values else listOf(value)
            coerceArgumentExpression(
                typeExpr = decodeModelInputType(type, schema),
                value = values.map {
                    decodeLiteral(
                        type.wrappedType as GraphQLInputType,
                        it,
                        variableValues,
                        schema,
                        variableField,
                    )
                },
            )
        }

        is GraphQLScalarType ->
            coerceArgumentExpression(
                decodeModelInputType(type, schema),
                decodeScalarLiteral(
                    schema,
                    schema.requireType(type.name) as Schema.Scalar,
                    value,
                ),
            )
        is GraphQLEnumType ->
            coerceArgumentExpression(
                decodeModelInputType(type, schema),
                (value as EnumValue).name,
            )

        is GraphQLInputObjectType ->
            decodeObjectLiteral(
                type,
                value as ObjectValue,
                variableValues,
                schema,
                variableField,
            )

        else -> error("Unexpected input type: $type")
    }
}

private fun decodeScalarLiteral(
    schema: Schema,
    scalarType: Schema.Scalar,
    value: GraphQLValue<*>,
): EngineSimpleData =
    when (scalarType.name) {
        "Int" ->
            (value as IntValue).value.intValueExact()
        "Float" ->
            when (value) {
                is FloatValue -> value.value.toDouble()
                is IntValue -> value.value.toDouble()
                else -> error("Invalid Float literal: $value")
            }

        "String" -> (value as StringValue).value!!
        "Boolean" -> (value as BooleanValue).isValue
        "ID" ->
            when (value) {
                is StringValue -> value.value!!
                is IntValue -> value.value.toString()
                else -> error("Invalid ID literal: $value")
            }
        else -> error("Unsupported scalar: ${scalarType.name}")
    }

private inline fun decodeInputObjectFields(
    type: GraphQLInputObjectType,
    isFieldSupplied: (String) -> Boolean,
    decodeSupplied: (GraphQLInputType, String) -> Any?,
    variableValues: Map<String, EngineInputData?>,
    schema: Schema,
): Any {
    val fields =
        buildMap<String, Any?> {
            type.fieldDefinitions.forEach { field ->
                when {
                    isFieldSupplied(field.name) ->
                        put(field.name, decodeSupplied(field.type, field.name))

                    field.hasSetDefaultValue() ->
                        put(
                            field.name,
                            decodeInputValue(
                                field.type,
                                field.inputFieldDefaultValue,
                                variableValues,
                                schema,
                            ),
                        )
                }
            }
        }
    return requireNotNull(
        coerceArgumentExpression(
            typeExpr =
                TypeExpr.Named.of(
                    schema.requireType(type.name) as Schema.Input,
                ),
            value = fields,
        ),
    )
}

private fun decodeObjectLiteral(
    type: GraphQLInputObjectType,
    value: ObjectValue,
    variableValues: Map<String, EngineInputData?>,
    schema: Schema,
    variableField: Schema.ObjectField?,
): Any {
    val suppliedFields = value.objectFields.associateBy { it.name }
    return decodeInputObjectFields(
        type = type,
        isFieldSupplied = { suppliedFields.containsKey(it) },
        decodeSupplied = { fieldType, fieldName ->
            decodeLiteral(
                fieldType,
                suppliedFields.getValue(fieldName).value,
                variableValues,
                schema,
                variableField,
            )
        },
        variableValues = variableValues,
        schema = schema,
    )
}

private fun decodeExternal(
    type: GraphQLInputType,
    value: Any?,
    variableValues: Map<String, EngineInputData?>,
    schema: Schema,
): EngineInputData? {
    if (value == null) {
        return null
    }

    return when (type) {
        is GraphQLNonNull ->
            decodeExternal(
                type.wrappedType as GraphQLInputType,
                value,
                variableValues,
                schema,
            )
        is GraphQLList -> {
            val values = if (value is Iterable<*>) value.toList() else listOf(value)
            val data: EngineInputListData =
                values.map {
                    decodeExternal(
                        type.wrappedType as GraphQLInputType,
                        it,
                        variableValues,
                        schema,
                    )
                }
            data
        }

        is GraphQLScalarType ->
            decodeScalarExternal(
                schema,
                schema.requireType(type.name) as Schema.Scalar,
                value,
            )
        is GraphQLEnumType -> value.toString()

        is GraphQLInputObjectType ->
            decodeObjectExternal(type, value, variableValues, schema)
        else -> error("Unexpected input type: $type")
    }
}

internal fun decodeExternalInputValue(
    type: GraphQLInputType,
    value: Any?,
    schema: Schema,
): Any? =
    coerceArgumentExpression(
        decodeModelInputType(type, schema),
        decodeExternal(type, value, emptyMap(), schema),
    )

private fun decodeScalarExternal(
    schema: Schema,
    scalarType: Schema.Scalar,
    value: Any,
): EngineSimpleData =
    when (scalarType.name) {
        "Int" -> (value as Number).toInt()
        "Float" -> (value as Number).toDouble()
        "String" -> value as String
        "Boolean" -> value as Boolean
        "ID" -> value.toString()
        else -> error("Unsupported scalar: ${scalarType.name}")
    }

private fun decodeModelInputType(
    type: GraphQLInputType,
    schema: Schema,
): TypeExpr<Schema.InputTypeDef> =
    when (type) {
        is GraphQLNonNull ->
            when (val wrapped = type.wrappedType) {
                is GraphQLList ->
                    TypeExpr.List.of(
                        elementType =
                            decodeModelInputType(
                                wrapped.wrappedType as GraphQLInputType,
                                schema,
                            ),
                        isNullable = false,
                    )
                is GraphQLNamedType ->
                    TypeExpr.Named.of(
                        schema.requireType(wrapped.name) as Schema.InputTypeDef,
                        isNullable = false,
                    )
                else -> error("Unexpected non-null input type: $wrapped")
            }
        is GraphQLList ->
            TypeExpr.List.of(
                elementType =
                    decodeModelInputType(
                        type.wrappedType as GraphQLInputType,
                        schema,
                    ),
            )
        is GraphQLNamedType ->
            TypeExpr.Named.of(schema.requireType(type.name) as Schema.InputTypeDef)
        else -> error("Unexpected input type: $type")
    }

internal fun decodeModelOutputType(
    type: GraphQLOutputType,
    schema: Schema,
): TypeExpr<Schema.OutputTypeDef> =
    when (type) {
        is GraphQLNonNull ->
            when (val wrapped = type.wrappedType) {
                is GraphQLList ->
                    TypeExpr.List.of(
                        elementType =
                            decodeModelOutputType(
                                wrapped.wrappedType as GraphQLOutputType,
                                schema,
                            ),
                        isNullable = false,
                    )
                is GraphQLNamedType ->
                    TypeExpr.Named.of(
                        schema.requireType(wrapped.name) as Schema.OutputTypeDef,
                        isNullable = false,
                    )
                else -> error("Unexpected non-null output type: $wrapped")
            }
        is GraphQLList ->
            TypeExpr.List.of(
                elementType =
                    decodeModelOutputType(
                        type.wrappedType as GraphQLOutputType,
                        schema,
                    ),
            )
        is GraphQLNamedType ->
            TypeExpr.Named.of(schema.requireType(type.name) as Schema.OutputTypeDef)
        else -> error("Unexpected output type: $type")
    }

private fun decodeObjectExternal(
    type: GraphQLInputObjectType,
    value: Any,
    variableValues: Map<String, EngineInputData?>,
    schema: Schema,
): EngineInputObjectData {
    val valueMap = value as Map<*, *>
    val decoded =
        decodeInputObjectFields(
        type = type,
        isFieldSupplied = { valueMap.containsKey(it) },
        decodeSupplied = { fieldType, fieldName ->
            coerceArgumentExpression(
                decodeModelInputType(fieldType, schema),
                decodeExternal(fieldType, valueMap[fieldName], variableValues, schema),
            )
        },
        variableValues = variableValues,
        schema = schema,
    )
    return requireType(decoded)
}

private inline fun <reified T> requireType(value: EngineInputData): T {
    require(value is T)
    return value
}

private class EnumTypeImpl(
    override val name: String,
    valueNames: Set<String>,
) : Schema.Enum {
    override val values: Collection<Schema.EnumValue> =
        valueNames.map { name -> EnumValueImpl(name, this) }
}

private class EnumValueImpl(
    override val name: String,
    override val containingDef: Schema.Enum,
) : Schema.EnumValue

private class ObjectTypeImpl(
    override val name: String,
    override val possibleObjectTypes: Set<Schema.Object>,
) : Schema.Object {
    private val mutableFields = mutableListOf<Schema.ObjectField>()
    override val fields: Collection<Schema.ObjectField>
        get() = mutableFields

    private var definition: GraphQLObjectType? = null

    override val graphQLJavaDefinition: GraphQLObjectType
        get() = checkNotNull(definition) { "$name GraphQL-Java definition is not initialized" }

    fun add(field: Schema.ObjectField) {
        mutableFields += field
    }

    fun initializeGraphQLJavaDefinition(definition: GraphQLObjectType) {
        check(this.definition == null) {
            "$name GraphQL-Java definition is already initialized"
        }
        require(definition.name == name) {
            "GraphQL-Java definition ${definition.name} does not represent $name"
        }
        this.definition = definition
    }
}

private class InterfaceTypeImpl(
    override val name: String,
    override val possibleObjectTypes: Set<Schema.Object>,
) : Schema.Interface {
    private val mutableFields = mutableListOf<Schema.Field>()
    override val fields: Collection<Schema.Field>
        get() = mutableFields

    fun add(field: Schema.Field) {
        mutableFields += field
    }
}

private class UnionTypeImpl(
    override val name: String,
    override val possibleObjectTypes: Set<Schema.Object>,
) : Schema.Union {
    private val mutableFields = mutableListOf<Schema.Field>()
    override val fields: Collection<Schema.Field>
        get() = mutableFields

    fun add(field: Schema.Field) {
        mutableFields += field
    }
}

private class InputObjectTypeImpl(
    override val name: String,
) : Schema.Input {
    private val mutableFields = mutableListOf<Schema.InputField>()
    override val fields: Collection<Schema.InputField>
        get() = mutableFields

    fun add(field: Schema.InputField) {
        mutableFields += field
    }
}

private class OutputFieldImpl(
    override val name: String,
    override val containingDef: Schema.CompositeTypeDef,
    override val type: TypeExpr<Schema.OutputTypeDef>,
    argsFactory: (Schema.Field) -> Collection<Schema.FieldArg>,
) : Schema.Field {
    override val args: Collection<Schema.FieldArg> = argsFactory(this)
}

private class ObjectFieldImpl(
    override val name: String,
    override val containingDef: Schema.Object,
    override val type: TypeExpr<Schema.OutputTypeDef>,
    argsFactory: (Schema.Field) -> Collection<Schema.FieldArg>,
) : Schema.ObjectField {
    override val args: Collection<Schema.FieldArg> = argsFactory(this)
}

private fun outputFieldOf(
    name: String,
    containingDef: Schema.CompositeTypeDef,
    type: TypeExpr<Schema.OutputTypeDef>,
    argsFactory: (Schema.Field) -> Collection<Schema.FieldArg> = { emptyList() },
): Schema.Field =
    if (containingDef is Schema.Object) {
        ObjectFieldImpl(name, containingDef, type, argsFactory)
    } else {
        OutputFieldImpl(name, containingDef, type, argsFactory)
    }

private class InputFieldImpl(
    override val name: String,
    override val containingDef: Schema.Input,
    override val type: TypeExpr<Schema.InputTypeDef>,
    override val defaultValue: CoercedDefaultValue,
) : Schema.InputField

private class FieldArgumentImpl(
    override val name: String,
    override val containingDef: Schema.Field,
    override val type: TypeExpr<Schema.InputTypeDef>,
    override val defaultValue: CoercedDefaultValue,
) : Schema.FieldArg

private fun <T> fieldArgsOf(
    definitions: Collection<T>,
    containingDef: Schema.Field,
    name: (T) -> String,
    createField: (T) -> Schema.FieldArg,
): Collection<Schema.FieldArg> {
    val fields = mutableListOf<Schema.FieldArg>()
    definitions.forEach { definition ->
        val argumentName = name(definition)
        require(fields.none { it.name == argumentName }) {
            "Duplicate field argument: $argumentName"
        }
        val field = createField(definition)
        require(field.name == argumentName) {
            "Field argument name does not match its declared name"
        }
        require(field.containingDef == containingDef) {
            "Field argument does not reference its containing field"
        }
        fields += field
    }
    return fields.toList()
}

private class DecodedSchema(
    override val queryTypeDef: Schema.Object,
    override val types: Map<String, Schema.TypeDef>,
) : Schema
