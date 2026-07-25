package model

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value
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
import graphql.schema.GraphQLUnionType
import graphql.schema.InputValueWithState

internal class GJSchemaDecoder(
    private val graphQLSchema: GraphQLSchema,
) {
    private val types = linkedMapOf<String, Schema.Type>()
    private val compositeFields =
        linkedMapOf<Schema.CompositeType, MutableMap<String, Schema.OutputField>>()
    private val inputFields =
        linkedMapOf<Schema.InputObjectType, MutableMap<String, Schema.InputField>>()
    private val possibleTypeSets =
        linkedMapOf<Schema.CompositeType, MutableSet<Schema.ObjectType>>()
    private lateinit var schema: Schema
    private lateinit var noVariableValues: VariableBindings

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

        val query = types["Query"] as Schema.ObjectType
        schema =
            DecodedSchema(
                query = query,
                types = types.toMap(),
                graphQLSchema = graphQLSchema,
            )
        noVariableValues = VariableBindings.from(schema, emptyMap())

        populatePossibleTypes()
        populateInputFields()
        populateCompositeFields()

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
                            Schema.EnumType(
                                typeName = graphQLType.name,
                                values = graphQLType.values.mapTo(linkedSetOf()) { it.name },
                            )

                    is GraphQLInputObjectType -> {
                        val fields = linkedMapOf<String, Schema.InputField>()
                        val type = Schema.InputObjectType(graphQLType.name, fields)
                        types[type.typeName] = type
                        inputFields[type] = fields
                    }

                    is GraphQLObjectType, is GraphQLInterfaceType, is GraphQLUnionType -> {
                        val fields = linkedMapOf<String, Schema.OutputField>()
                        val possibleTypes = linkedSetOf<Schema.ObjectType>()
                        val type: Schema.CompositeType = when (graphQLType) {
                            is GraphQLObjectType ->
                                Schema.ObjectType(graphQLType.name, fields, possibleTypes)
                            is GraphQLInterfaceType ->
                                Schema.InterfaceType(graphQLType.name, fields, possibleTypes)
                            else ->
                                Schema.UnionType((graphQLType as GraphQLUnionType).name, fields, possibleTypes)
                        }
                        if (type is Schema.ObjectType) possibleTypes += type
                        registerComposite(type, fields, possibleTypes)
                    }

                    else -> error("Unexpected GraphQL type: $graphQLType")
                }
            }
    }

    private fun registerComposite(
        type: Schema.CompositeType,
        fields: MutableMap<String, Schema.OutputField>,
        possibleTypes: MutableSet<Schema.ObjectType>,
    ) {
        types[type.typeName] = type
        compositeFields[type] = fields
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
                        Schema.InputField(
                            fieldName = graphQLField.name,
                            containingType = modelType,
                            type = decodeInputType(graphQLField.type),
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
                val modelFields = compositeFields.getValue(modelType)
                graphQLType.fieldDefinitions.forEach { graphQLField ->
                    val arguments =
                        Schema.FieldArguments.of(
                            definitions = graphQLField.arguments,
                            name = { it.name },
                        ) { graphQLArgument, containingType ->
                            Schema.FieldArgument(
                                argumentName = graphQLArgument.name,
                                containingType = containingType,
                                type = decodeInputType(graphQLArgument.type),
                                defaultValue =
                                    decodeDefault(
                                        graphQLArgument.type,
                                        graphQLArgument.argumentDefaultValue,
                                    ),
                            )
                        }
                    val modelField =
                        Schema.OutputField(
                            fieldName = graphQLField.name,
                            containingType = modelType,
                            type = decodeOutputType(graphQLField.type),
                            arguments = arguments,
                        )
                    modelFields[modelField.fieldName] = modelField
                }
            }
    }

    private fun addTypeNameField(type: Schema.CompositeType) {
        val field =
            Schema.OutputField(
                fieldName = "__typename",
                containingType = type,
                type = Schema.TypeExpr.Named(Schema.StringType, isNullable = false),
                arguments = Schema.NoArguments,
            )
        compositeFields.getValue(type)[field.fieldName] = field
    }

    private fun decodeOutputType(type: GraphQLOutputType): Schema.TypeExpr<Schema.OutputType> =
        decodeType(type) { typeName ->
            types.getValue(typeName) as Schema.OutputType
        }

    private fun decodeInputType(type: GraphQLInputType): Schema.TypeExpr<Schema.InputType> =
        decodeType(type) { typeName ->
            resolveInputType(typeName)
        }

    private fun resolveInputType(typeName: String): Schema.InputType =
        types.getValue(typeName) as Schema.InputType

    private fun <T : Schema.Type> decodeType(
        type: GraphQLType,
        resolveNamedType: (String) -> T,
    ): Schema.TypeExpr<T> =
        when (type) {
            is GraphQLNonNull ->
                decodeNonNullType(type.wrappedType, resolveNamedType)

            is GraphQLList ->
                Schema.TypeExpr.List(
                    elementType = decodeType(type.wrappedType, resolveNamedType),
                    isNullable = true,
                )

            is GraphQLNamedType ->
                Schema.TypeExpr.Named(
                    baseType = resolveNamedType(type.name),
                    isNullable = true,
                )

            else -> error("Unexpected GraphQL type expression: $type")
        }

    private fun <T : Schema.Type> decodeNonNullType(
        type: GraphQLType,
        resolveNamedType: (String) -> T,
    ): Schema.TypeExpr<T> =
        when (type) {
            is GraphQLList ->
                Schema.TypeExpr.List(
                    elementType = decodeType(type.wrappedType, resolveNamedType),
                    isNullable = false,
                )

            is GraphQLNamedType ->
                Schema.TypeExpr.Named(
                    baseType = resolveNamedType(type.name),
                    isNullable = false,
                )

            else -> error("Unexpected non-null GraphQL type expression: $type")
        }

    private fun decodeDefault(
        type: GraphQLInputType,
        value: InputValueWithState,
    ): Schema.DefaultValue =
        if (value.isNotSet) {
            Schema.DefaultValue.Absent
        } else {
            Schema.DefaultValue.Present(
                decodeInputValue(
                    type,
                    value,
                    noVariableValues,
                    schema,
                ),
            )
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
    variableValues: VariableBindings,
    schema: Schema,
): Schema.InputValue? =
    if (value.isLiteral) {
        decodeLiteral(type, value.value as Value<*>, variableValues, schema)
    } else {
        decodeExternal(type, value.value, variableValues, schema)
    }

internal fun decodeLiteral(
    type: GraphQLInputType,
    value: Value<*>,
    variableValues: VariableBindings,
    schema: Schema,
): Schema.InputValue? {
    if (value is VariableReference) {
        return if (variableValues.containsKey(value.name)) {
            variableValues.getValue(value.name)
        } else {
            schema.variableValue(value.name)
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
            )

        is GraphQLList -> {
            val values = if (value is ArrayValue) value.values else listOf(value)
            schema.inputListValue(
                values.map {
                    decodeLiteral(
                        type.wrappedType as GraphQLInputType,
                        it,
                        variableValues,
                        schema,
                    )
                },
            )
        }

        is GraphQLScalarType ->
            decodeScalarLiteral(
                schema,
                schema.type(type.name) as Schema.ScalarType,
                value,
            )
        is graphql.schema.GraphQLEnumType ->
            schema.enumValue(
                schema.type(type.name) as Schema.EnumType,
                (value as EnumValue).name,
            )

        is GraphQLInputObjectType ->
            decodeObjectLiteral(
                type,
                value as ObjectValue,
                variableValues,
                schema,
            )

        else -> error("Unexpected input type: $type")
    }
}

private fun decodeScalarLiteral(
    schema: Schema,
    scalarType: Schema.ScalarType,
    value: Value<*>,
): Schema.InputValue =
    when (scalarType) {
        Schema.IntType ->
            schema.intValue((value as IntValue).value.intValueExact())
        Schema.FloatType ->
            schema.floatValue(
                when (value) {
                    is FloatValue -> value.value.toDouble()
                    is IntValue -> value.value.toDouble()
                    else -> error("Invalid Float literal: $value")
                },
            )

        Schema.StringType -> schema.stringValue((value as StringValue).value!!)
        Schema.BooleanType -> schema.booleanValue((value as BooleanValue).isValue)
        Schema.IDType ->
            schema.idValue(
                when (value) {
                    is StringValue -> value.value!!
                    is IntValue -> value.value.toString()
                    else -> error("Invalid ID literal: $value")
                },
            )
    }

private fun decodeObjectLiteral(
    type: GraphQLInputObjectType,
    value: ObjectValue,
    variableValues: VariableBindings,
    schema: Schema,
): Schema.InputValue {
    val suppliedFields = value.objectFields.associateBy { it.name }
    val fields =
        buildMap<String, Schema.InputValue?> {
            type.fieldDefinitions.forEach { field ->
                val suppliedValue = suppliedFields[field.name]
                when {
                    suppliedValue != null ->
                        put(
                            field.name,
                            decodeLiteral(
                                field.type,
                                suppliedValue.value,
                                variableValues,
                                schema,
                            ),
                        )

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
    return schema.inputObjectValue(
        type = schema.type(type.name) as Schema.InputObjectType,
        fields = fields,
    )
}

private fun decodeExternal(
    type: GraphQLInputType,
    value: Any?,
    variableValues: VariableBindings,
    schema: Schema,
): Schema.InputValue? {
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
            schema.inputListValue(
                values.map {
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
            schema.enumValue(
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
): Schema.InputValue =
    when (scalarType) {
        Schema.IntType -> schema.intValue((value as Number).toInt())
        Schema.FloatType -> schema.floatValue((value as Number).toDouble())
        Schema.StringType -> schema.stringValue(value as String)
        Schema.BooleanType -> schema.booleanValue(value as Boolean)
        Schema.IDType -> schema.idValue(value.toString())
    }

private fun decodeObjectExternal(
    type: GraphQLInputObjectType,
    value: Any,
    variableValues: VariableBindings,
    schema: Schema,
): Schema.InputValue {
    val valueMap = value as Map<*, *>
    val fields =
        buildMap<String, Schema.InputValue?> {
            type.fieldDefinitions.forEach { field ->
                when {
                    valueMap.containsKey(field.name) ->
                        put(
                            field.name,
                            decodeExternal(
                                field.type,
                                valueMap[field.name],
                                variableValues,
                                schema,
                            ),
                        )

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
    return schema.inputObjectValue(
        type = schema.type(type.name) as Schema.InputObjectType,
        fields = fields,
    )
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

    override fun possibleObjectTypes(typeName: String): Set<String>? =
        (type(typeName) as? Schema.CompositeType)
            ?.possibleTypes
            ?.mapTo(linkedSetOf()) { it.typeName }

    override fun spreadableTypes(parentTypeName: String): Set<String>? {
        val parentType = type(parentTypeName) as? Schema.CompositeType ?: return null
        return types.values
            .filterIsInstance<Schema.CompositeType>()
            .filter { candidate ->
                candidate === parentType ||
                    candidate.possibleTypes.any(parentType.possibleTypes::contains)
            }.mapTo(linkedSetOf()) { it.typeName }
    }

    override fun isSpreadable(
        parentTypeName: String,
        fragmentTypeName: String,
    ): Boolean? {
        val parentType = type(parentTypeName) as? Schema.CompositeType ?: return null
        val fragmentType = type(fragmentTypeName) as? Schema.CompositeType ?: return null
        return fragmentType === parentType ||
            fragmentType.possibleTypes.any(parentType.possibleTypes::contains)
    }

    override fun relation(
        aTypeName: String,
        bTypeName: String,
    ): Schema.TypeRelation? {
        val a = type(aTypeName) as? Schema.CompositeType ?: return null
        val b = type(bTypeName) as? Schema.CompositeType ?: return null
        return when {
            a === b -> Schema.TypeRelation.SAME
            isNominallyWider(a, b) -> Schema.TypeRelation.WIDER_THAN
            isNominallyWider(b, a) -> Schema.TypeRelation.NARROWER_THAN
            a.possibleTypes.any(b.possibleTypes::contains) -> Schema.TypeRelation.COPARENT
            else -> Schema.TypeRelation.NONE
        }
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

private fun implementsInterface(
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
