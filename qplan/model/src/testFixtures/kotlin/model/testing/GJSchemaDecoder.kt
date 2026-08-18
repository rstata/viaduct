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
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.InputValueWithState
import model.OpenValue
import model.Schema
import model.TypeExpr
import model.Value

/** Names reserved for fixture-generated canonical node bridge types and fields. */
internal const val SYNTHETIC_NAME_TOKEN = "V_A"
internal const val NODE_BRIDGE_TYPE_SUFFIX = "_V_A_Bridge"
internal const val NODE_BRIDGE_FIELD_SUFFIX = "_V_A_node"
internal const val NODE_BRIDGE_ID_FIELD = "id"
internal const val NODE_BRIDGE_PAYLOAD_FIELD = "node"
internal const val TYPED_NODE_ID_PREFIX = "\$node:"

internal fun nodeBridgeTypeName(nodeType: Schema.CompositeType): String =
    nodeType.typeName + NODE_BRIDGE_TYPE_SUFFIX

internal fun nodeBridgeFieldName(sourceFieldName: String): String =
    sourceFieldName + NODE_BRIDGE_FIELD_SUFFIX

/**
 * Decodes external GraphQL SDL into the canonical fixture schema.
 *
 * For each Node subtype used as a source field's named output type, the decoded schema contains one
 * concrete `T_V_A_Bridge` type with `id: ID` and `node: T`. A source field `foo: W<T>` is omitted
 * and represented by `foo_V_A_node: W<T_V_A_Bridge>`, preserving its arguments, list structure,
 * and nullability. These definitions belong only to the lowered reasoning world and are never
 * parsed from GraphQL text.
 */
internal class GJSchemaDecoder(
    private val graphQLSchema: GraphQLSchema,
) {
    private val types = linkedMapOf<String, Schema.Type>()
    private val abstractCompositeFields =
        linkedMapOf<Schema.CompositeType, MutableMap<String, Schema.OutputField>>()
    private val objectFields =
        linkedMapOf<Schema.ObjectType, MutableMap<String, Schema.ObjectField>>()
    private val inputFields =
        linkedMapOf<Schema.InputObjectType, MutableMap<String, Schema.InputField>>()
    private val possibleTypeSets =
        linkedMapOf<Schema.CompositeType, MutableSet<Schema.ObjectType>>()
    private lateinit var schema: Schema
    private val noVariableValues: Map<String, Value.Input?> = emptyMap()

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
        createNodeBridgeTypeShells()

        val query = types["Query"] as Schema.ObjectType
        schema =
            DecodedSchema(
                query = query,
                types = types.toMap(),
                graphQLSchema = graphQLSchema,
            )
        populatePossibleTypes()
        populateInputFields()
        populateCompositeFields()
        populateNodeBridgeTypes()

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
            types[scalar.typeName] = scalar
        }
    }

    private fun createTypeShells() {
        graphQLSchema.allTypesAsList
            .filterNot { it.name.startsWith("__") }
            .forEach { graphQLType ->
                when (graphQLType) {
                    is GraphQLScalarType -> types[graphQLType.name]!!

                    is graphql.schema.GraphQLEnumType ->
                        types[graphQLType.name] =
                            EnumTypeImpl(
                                typeName = graphQLType.name,
                                values = graphQLType.values.mapTo(linkedSetOf()) { it.name },
                            )

                    is GraphQLInputObjectType -> {
                        val fields = linkedMapOf<String, Schema.InputField>()
                        val type = InputObjectTypeImpl(graphQLType.name, fields)
                        types[type.typeName] = type
                        inputFields[type] = fields
                    }

                    is GraphQLObjectType -> {
                        val fields = linkedMapOf<String, Schema.ObjectField>()
                        val possibleTypes = linkedSetOf<Schema.ObjectType>()
                        val type = ObjectTypeImpl(graphQLType.name, fields, possibleTypes)
                        possibleTypes += type
                        types[type.typeName] = type
                        objectFields[type] = fields
                        possibleTypeSets[type] = possibleTypes
                    }

                    is GraphQLInterfaceType, is GraphQLUnionType -> {
                        val fields = linkedMapOf<String, Schema.OutputField>()
                        val possibleTypes = linkedSetOf<Schema.ObjectType>()
                        val type: Schema.CompositeType =
                            when (graphQLType) {
                            is GraphQLInterfaceType ->
                                InterfaceTypeImpl(graphQLType.name, fields, possibleTypes)
                            else ->
                                UnionTypeImpl((graphQLType as GraphQLUnionType).name, fields, possibleTypes)
                            }
                        registerComposite(type, fields, possibleTypes)
                    }

                    else -> error("Unexpected GraphQL type: $graphQLType")
                }
            }
    }

    private fun createNodeBridgeTypeShells() {
        usedNodeOutputTypeNames().forEach { nodeTypeName ->
            val bridgeTypeName = nodeTypeName + NODE_BRIDGE_TYPE_SUFFIX
            require(bridgeTypeName !in types) {
                "Synthetic node bridge type collides with $bridgeTypeName"
            }
            val fields = linkedMapOf<String, Schema.ObjectField>()
            val possibleTypes = linkedSetOf<Schema.ObjectType>()
            val bridgeType = ObjectTypeImpl(bridgeTypeName, fields, possibleTypes)
            possibleTypes += bridgeType
            types[bridgeTypeName] = bridgeType
            objectFields[bridgeType] = fields
            possibleTypeSets[bridgeType] = possibleTypes
        }
    }

    private fun usedNodeOutputTypeNames(): Set<String> {
        val node = graphQLSchema.getType("Node") as? GraphQLInterfaceType ?: return emptySet()
        return graphQLSchema.allTypesAsList
            .filterIsInstance<graphql.schema.GraphQLFieldsContainer>()
            .filter { it is GraphQLObjectType || it is GraphQLInterfaceType }
            .flatMap { it.fieldDefinitions }
            .map { GraphQLTypeUtil.unwrapAll(it.type) }
            .filterIsInstance<GraphQLImplementingType>()
            .filter { outputType ->
                outputType.name == node.name ||
                    implementsInterface(node.name, outputType)
            }.mapTo(linkedSetOf()) { it.name }
    }

    private fun registerComposite(
        type: Schema.CompositeType,
        fields: MutableMap<String, Schema.OutputField>,
        possibleTypes: MutableSet<Schema.ObjectType>,
    ) {
        types[type.typeName] = type
        abstractCompositeFields[type] = fields
        possibleTypeSets[type] = possibleTypes
    }

    private fun populatePossibleTypes() {
        graphQLSchema.allTypesAsList.forEach { graphQLType ->
            when (graphQLType) {
                is GraphQLInterfaceType -> {
                    val modelType = types.getValue(graphQLType.name) as Schema.InterfaceType
                    graphQLSchema.allTypesAsList
                        .filterIsInstance<GraphQLObjectType>()
                        .filter { implementsInterface(graphQLType.name, it) }
                        .mapTo(possibleTypeSets.getValue(modelType)) {
                            types.getValue(it.name) as Schema.ObjectType
                        }
                }

                is GraphQLUnionType -> {
                    val modelType = types.getValue(graphQLType.name) as Schema.UnionType
                    graphQLType.types.mapTo(possibleTypeSets.getValue(modelType)) {
                        types.getValue(it.name) as Schema.ObjectType
                    }
                }
            }
        }
    }

    private fun populateInputFields() {
        graphQLSchema.allTypesAsList
            .filterIsInstance<GraphQLInputObjectType>()
            .forEach { graphQLType ->
                val modelType = types.getValue(graphQLType.name) as Schema.InputObjectType
                val modelFields = inputFields.getValue(modelType)
                graphQLType.fieldDefinitions.forEach { graphQLField ->
                    modelFields[graphQLField.name] =
                        InputFieldImpl(
                            fieldName = graphQLField.name,
                            containingType = modelType,
                            typeExpr = decodeInputType(graphQLField.type),
                            defaultValue =
                                decodeDefault(
                                    graphQLField.type,
                                    graphQLField.inputFieldDefaultValue,
                                ),
                        )
                }
            }
    }

    private fun populateCompositeFields() {
        types.values
            .filterIsInstance<Schema.CompositeType>()
            .forEach(::addTypeNameField)

        graphQLSchema.allTypesAsList
            .filterIsInstance<graphql.schema.GraphQLFieldsContainer>()
            .filter { it is GraphQLObjectType || it is GraphQLInterfaceType }
            .filterNot { it.name.startsWith("__") }
            .forEach { graphQLType ->
                val modelType = types.getValue(graphQLType.name) as Schema.CompositeType
                graphQLType.fieldDefinitions.forEach { graphQLField ->
                    val sourceTypeExpr = decodeOutputType(graphQLField.type)
                    val arguments =
                        fieldArgumentsOf(
                            definitions = graphQLField.arguments,
                            name = { it.name },
                        ) { graphQLArgument, containingType ->
                            FieldArgumentImpl(
                                argumentName = graphQLArgument.name,
                                containingType = containingType,
                                typeExpr = decodeInputType(graphQLArgument.type),
                                defaultValue =
                                    decodeDefault(
                                        graphQLArgument.type,
                                        graphQLArgument.argumentDefaultValue,
                                    ),
                            )
                        }
                    val modelField =
                        outputFieldOf(
                            fieldName =
                                if (isNodeType(sourceTypeExpr.baseType)) {
                                    nodeBridgeFieldName(graphQLField.name)
                                } else {
                                    graphQLField.name
                                },
                            containingType = modelType,
                            typeExpr =
                                if (isNodeType(sourceTypeExpr.baseType)) {
                                    nodeBridgeTypeExpr(sourceTypeExpr)
                                } else {
                                    sourceTypeExpr
                                },
                            arguments = arguments,
                        )
                    addCompositeField(modelType, modelField)
                }
            }
    }

    private fun addTypeNameField(type: Schema.CompositeType) {
        val field =
            outputFieldOf(
                fieldName = "__typename",
                containingType = type,
                typeExpr = TypeExpr.Named.of(Schema.StringType, isNullable = false),
                arguments = Schema.NoArguments,
            )
        addCompositeField(type, field)
    }

    private fun populateNodeBridgeTypes() {
        usedNodeOutputTypeNames().forEach { nodeTypeName ->
            val nodeType = types.getValue(nodeTypeName) as Schema.CompositeType
            val bridgeType = types.getValue(nodeTypeName + NODE_BRIDGE_TYPE_SUFFIX) as Schema.ObjectType
            addCompositeField(
                bridgeType,
                outputFieldOf(
                    fieldName = NODE_BRIDGE_ID_FIELD,
                    containingType = bridgeType,
                    typeExpr = TypeExpr.Named.of(Schema.IDType),
                    arguments = Schema.NoArguments,
                ),
            )
            addCompositeField(
                bridgeType,
                outputFieldOf(
                    fieldName = NODE_BRIDGE_PAYLOAD_FIELD,
                    containingType = bridgeType,
                    typeExpr = TypeExpr.Named.of(nodeType),
                    arguments = Schema.NoArguments,
                ),
            )
        }
    }

    private fun isNodeType(type: Schema.OutputType): Boolean {
        val nodeType = types["Node"] as? Schema.InterfaceType ?: return false
        if (type !is Schema.CompositeType) return false
        return schema.relation(nodeType, type) in
            setOf(
                Schema.TypeRelation.SAME,
                Schema.TypeRelation.WIDER_THAN,
            )
    }

    private fun addCompositeField(
        type: Schema.CompositeType,
        field: Schema.OutputField,
    ) {
        when (type) {
            is Schema.ObjectType -> objectFields.getValue(type)[field.fieldName] = field as Schema.ObjectField
            else -> abstractCompositeFields.getValue(type)[field.fieldName] = field
        }
    }

    private fun nodeBridgeTypeExpr(
        typeExpr: TypeExpr<Schema.OutputType>,
    ): TypeExpr<Schema.OutputType> =
        when (typeExpr) {
            is TypeExpr.Named ->
                TypeExpr.Named.of(
                    baseType =
                        types.getValue(
                            (typeExpr.baseType as Schema.CompositeType).typeName +
                                NODE_BRIDGE_TYPE_SUFFIX,
                        ) as Schema.ObjectType,
                    isNullable = typeExpr.isNullable,
                )
            is TypeExpr.List ->
                TypeExpr.List.of(
                    elementType = nodeBridgeTypeExpr(typeExpr.elementType),
                    isNullable = typeExpr.isNullable,
                )
        }

    private fun decodeOutputType(type: GraphQLOutputType): TypeExpr<Schema.OutputType> =
        decodeModelOutputType(type, schema)

    private fun decodeInputType(type: GraphQLInputType): TypeExpr<Schema.InputType> =
        decodeType(type) { typeName ->
            types.getValue(typeName) as Schema.InputType
        }

    private fun <T : Schema.Type> decodeType(
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

    private fun <T : Schema.Type> decodeNonNullType(
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
    ): Value.Default =
        if (value.isNotSet) {
            Value.Default.Absent
        } else {
            val decoded =
                decodeInputValue(
                    type,
                    value,
                    noVariableValues,
                    schema,
                )
            check(decoded == null || decoded is OpenValue.Ground)
            Value.Default.of((decoded as? OpenValue.Ground)?.data)
        }

    private fun implementsInterface(
        interfaceName: String,
        implementingType: GraphQLImplementingType,
        visited: Set<String> = emptySet(),
    ): Boolean = implementsInterface(graphQLSchema, interfaceName, implementingType, visited)

}

internal fun decodeInputValue(
    type: GraphQLInputType,
    value: InputValueWithState,
    variableValues: Map<String, Value.Input?>,
    schema: Schema,
    variableField: Schema.ObjectField? = null,
): OpenValue? =
    if (value.isLiteral) {
        decodeLiteral(
            type,
            value.value as GraphQLValue<*>,
            variableValues,
            schema,
            variableField,
        )
    } else {
        OpenValue.of(
            decodeModelInputType(type, schema),
            decodeExternal(type, value.value, variableValues, schema),
        )
    }

internal fun decodeLiteral(
    type: GraphQLInputType,
    value: GraphQLValue<*>,
    variableValues: Map<String, Value.Input?>,
    schema: Schema,
    variableField: Schema.ObjectField? = null,
): OpenValue? {
    if (value is VariableReference) {
        return if (variableValues.containsKey(value.name)) {
            OpenValue.of(
                decodeModelInputType(type, schema),
                variableValues.getValue(value.name),
            )
        } else {
            requireNotNull(variableField) {
                "Unbound operation variable \$${value.name}"
            }
            Value.Variable.of(variableField, value.name)
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
            OpenValue.of(
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
            OpenValue.of(
                decodeModelInputType(type, schema),
                decodeScalarLiteral(
                    schema,
                    schema.type(type.name) as Schema.ScalarType,
                    value,
                ),
            )
        is graphql.schema.GraphQLEnumType ->
            OpenValue.of(
                decodeModelInputType(type, schema),
                Value.Enum.of(
                    schema.type(type.name) as Schema.EnumType,
                    (value as EnumValue).name,
                ),
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
    scalarType: Schema.ScalarType,
    value: GraphQLValue<*>,
): Value.Input =
    when (scalarType) {
        Schema.IntType ->
            Value.Int.of((value as IntValue).value.intValueExact())
        Schema.FloatType ->
            Value.Float.of(
                when (value) {
                    is FloatValue -> value.value.toDouble()
                    is IntValue -> value.value.toDouble()
                    else -> error("Invalid Float literal: $value")
                },
            )

        Schema.StringType -> Value.String.of((value as StringValue).value!!)
        Schema.BooleanType -> Value.Boolean.of((value as BooleanValue).isValue)
        Schema.IDType ->
            Value.ID.of(
                when (value) {
                    is StringValue -> value.value!!
                    is IntValue -> value.value.toString()
                    else -> error("Invalid ID literal: $value")
                },
            )
    }

private inline fun decodeInputObjectFields(
    type: GraphQLInputObjectType,
    isFieldSupplied: (String) -> Boolean,
    decodeSupplied: (GraphQLInputType, String) -> OpenValue?,
    variableValues: Map<String, Value.Input?>,
    schema: Schema,
): OpenValue {
    val fields =
        buildMap<String, OpenValue?> {
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
        OpenValue.of(
            typeExpr =
                TypeExpr.Named.of(
                    schema.type(type.name) as Schema.InputObjectType,
                ),
            value = fields,
        ),
    )
}

private fun decodeObjectLiteral(
    type: GraphQLInputObjectType,
    value: ObjectValue,
    variableValues: Map<String, Value.Input?>,
    schema: Schema,
    variableField: Schema.ObjectField?,
): OpenValue {
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
    variableValues: Map<String, Value.Input?>,
    schema: Schema,
): Value.Input? {
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
            Value.InputList.of(
                typeExpr = decodeModelInputType(type.wrappedType as GraphQLInputType, schema),
                values = values.map {
                    decodeExternal(
                        type.wrappedType as GraphQLInputType,
                        it,
                        variableValues,
                        schema,
                    )
                },
            )
        }

        is GraphQLScalarType ->
            decodeScalarExternal(
                schema,
                schema.type(type.name) as Schema.ScalarType,
                value,
            )
        is graphql.schema.GraphQLEnumType ->
            Value.Enum.of(
                schema.type(type.name) as Schema.EnumType,
                value.toString(),
            )

        is GraphQLInputObjectType ->
            decodeObjectExternal(type, value, variableValues, schema)
        else -> error("Unexpected input type: $type")
    }
}

private fun decodeScalarExternal(
    schema: Schema,
    scalarType: Schema.ScalarType,
    value: Any,
): Value.Input =
    when (scalarType) {
        Schema.IntType -> Value.Int.of((value as Number).toInt())
        Schema.FloatType -> Value.Float.of((value as Number).toDouble())
        Schema.StringType -> Value.String.of(value as String)
        Schema.BooleanType -> Value.Boolean.of(value as Boolean)
        Schema.IDType -> Value.ID.of(value.toString())
    }

private fun decodeModelInputType(
    type: GraphQLInputType,
    schema: Schema,
): TypeExpr<Schema.InputType> =
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
                        schema.type(wrapped.name) as Schema.InputType,
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
            TypeExpr.Named.of(schema.type(type.name) as Schema.InputType)
        else -> error("Unexpected input type: $type")
    }

internal fun decodeModelOutputType(
    type: GraphQLOutputType,
    schema: Schema,
): TypeExpr<Schema.OutputType> =
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
                        schema.type(wrapped.name) as Schema.OutputType,
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
            TypeExpr.Named.of(schema.type(type.name) as Schema.OutputType)
        else -> error("Unexpected output type: $type")
    }

private fun decodeObjectExternal(
    type: GraphQLInputObjectType,
    value: Any,
    variableValues: Map<String, Value.Input?>,
    schema: Schema,
): Value.Input {
    val valueMap = value as Map<*, *>
    val decoded =
        decodeInputObjectFields(
        type = type,
        isFieldSupplied = { valueMap.containsKey(it) },
        decodeSupplied = { fieldType, fieldName ->
            OpenValue.of(
                decodeModelInputType(fieldType, schema),
                decodeExternal(fieldType, valueMap[fieldName], variableValues, schema),
            )
        },
        variableValues = variableValues,
        schema = schema,
    )
    check(decoded is OpenValue.Ground)
    return decoded.data
}

private class EnumTypeImpl(
    override val typeName: String,
    override val values: Set<String>,
) : Schema.EnumType

private class ObjectTypeImpl(
    override val typeName: String,
    override val fields: Map<String, Schema.ObjectField>,
    override val possibleTypes: Set<Schema.ObjectType>,
) : Schema.ObjectType

private class InterfaceTypeImpl(
    override val typeName: String,
    override val fields: Map<String, Schema.OutputField>,
    override val possibleTypes: Set<Schema.ObjectType>,
) : Schema.InterfaceType

private class UnionTypeImpl(
    override val typeName: String,
    override val fields: Map<String, Schema.OutputField>,
    override val possibleTypes: Set<Schema.ObjectType>,
) : Schema.UnionType

private class InputObjectTypeImpl(
    override val typeName: String,
    override val fields: Map<String, Schema.InputField>,
) : Schema.InputObjectType

private class FieldArgumentsImpl(
    override val fields: Map<String, Schema.FieldArgument>,
) : Schema.FieldArguments.NonEmpty

private class OutputFieldImpl(
    override val fieldName: String,
    override val containingType: Schema.CompositeType,
    override val typeExpr: TypeExpr<Schema.OutputType>,
    override val arguments: Schema.FieldArguments,
) : Schema.OutputField

private class ObjectFieldImpl(
    override val fieldName: String,
    override val containingType: Schema.ObjectType,
    override val typeExpr: TypeExpr<Schema.OutputType>,
    override val arguments: Schema.FieldArguments,
) : Schema.ObjectField

private fun outputFieldOf(
    fieldName: String,
    containingType: Schema.CompositeType,
    typeExpr: TypeExpr<Schema.OutputType>,
    arguments: Schema.FieldArguments,
): Schema.OutputField =
    if (containingType is Schema.ObjectType) {
        ObjectFieldImpl(fieldName, containingType, typeExpr, arguments)
    } else {
        OutputFieldImpl(fieldName, containingType, typeExpr, arguments)
    }

private class InputFieldImpl(
    override val fieldName: String,
    override val containingType: Schema.InputObjectType,
    override val typeExpr: TypeExpr<Schema.InputType>,
    override val defaultValue: Value.Default,
) : Schema.InputField

private class FieldArgumentImpl(
    override val argumentName: String,
    override val containingType: Schema.FieldArguments,
    override val typeExpr: TypeExpr<Schema.InputType>,
    override val defaultValue: Value.Default,
) : Schema.FieldArgument

private fun <T> fieldArgumentsOf(
    definitions: Collection<T>,
    name: (T) -> String,
    createField: (T, Schema.FieldArguments) -> Schema.FieldArgument,
): Schema.FieldArguments {
    if (definitions.isEmpty()) return Schema.NoArguments

    val fields = linkedMapOf<String, Schema.FieldArgument>()
    val result = FieldArgumentsImpl(fields)
    definitions.forEach { definition ->
        val argumentName = name(definition)
        require(argumentName !in fields) { "Duplicate field argument: $argumentName" }
        val field = createField(definition, result)
        require(field.argumentName == argumentName) {
            "Field argument name does not match its map key"
        }
        require(field.containingType == result) {
            "Field argument does not reference its containing argument definition"
        }
        fields[argumentName] = field
    }
    return result
}

private class DecodedSchema(
    override val query: Schema.ObjectType,
    private val types: Map<String, Schema.Type>,
    private val graphQLSchema: GraphQLSchema,
) : Schema {
    override fun type(typeName: String): Schema.Type =
        types[typeName] ?: throw Schema.MissingSchemaElementException(typeName)

    override fun field(
        typeName: String,
        fieldName: String,
    ): Schema.OutputField {
        val containingType = type(typeName) as? Schema.CompositeType
        return containingType?.fields?.get(fieldName)
            ?: throw Schema.MissingSchemaElementException(typeName, fieldName)
    }

    override fun spreadableTypes(
        parentType: Schema.CompositeType,
    ): Set<Schema.CompositeType> =
        types.values
            .filterIsInstance<Schema.CompositeType>()
            .filter { candidate ->
                candidate == parentType ||
                    candidate.possibleTypes.any(parentType.possibleTypes::contains)
            }.toCollection(linkedSetOf())

    override fun isSpreadable(
        parentType: Schema.CompositeType,
        fragmentType: Schema.CompositeType,
    ): Boolean =
        fragmentType == parentType ||
            fragmentType.possibleTypes.any(parentType.possibleTypes::contains)

    override fun relation(
        a: Schema.CompositeType,
        b: Schema.CompositeType,
    ): Schema.TypeRelation =
        when {
            a == b -> Schema.TypeRelation.SAME
            isNominallyWider(a, b) -> Schema.TypeRelation.WIDER_THAN
            isNominallyWider(b, a) -> Schema.TypeRelation.NARROWER_THAN
            a.possibleTypes.any(b.possibleTypes::contains) -> Schema.TypeRelation.COPARENT
            else -> Schema.TypeRelation.NONE
        }

    private fun isNominallyWider(
        wider: Schema.CompositeType,
        narrower: Schema.CompositeType,
    ): Boolean =
        when (val widerType = graphQLSchema.getType(wider.typeName)) {
            is GraphQLInterfaceType ->
                (graphQLSchema.getType(narrower.typeName) as? GraphQLImplementingType)
                    ?.let { implementsInterface(widerType.name, it) } == true

            is GraphQLUnionType ->
                graphQLSchema.getType(narrower.typeName) is GraphQLObjectType &&
                    widerType.types.any { it.name == narrower.typeName }

            else -> false
        }

    private fun implementsInterface(
        interfaceName: String,
        implementingType: GraphQLImplementingType,
        visited: Set<String> = emptySet(),
    ): Boolean = implementsInterface(graphQLSchema, interfaceName, implementingType, visited)
}

internal fun implementsInterface(
    schema: GraphQLSchema,
    interfaceName: String,
    implementingType: GraphQLImplementingType,
    visited: Set<String> = emptySet(),
): Boolean {
    if (implementingType.name in visited) return false
    val nextVisited = visited + implementingType.name
    return implementingType.interfaces.any { implementedInterface ->
        implementedInterface.name == interfaceName ||
            (schema.getType(implementedInterface.name) as GraphQLImplementingType).let {
                implementsInterface(schema, interfaceName, it, nextVisited)
            }
    }
}
