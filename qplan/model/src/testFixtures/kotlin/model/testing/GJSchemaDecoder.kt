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
import graphql.schema.GraphQLUnionType
import graphql.schema.InputValueWithState
import model.Schema
import model.TypeExpr
import model.Value
import model.VariableBindings

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
        noVariableValues = VariableBindings.from(emptyMap())

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

                    is GraphQLObjectType, is GraphQLInterfaceType, is GraphQLUnionType -> {
                        val fields = linkedMapOf<String, Schema.OutputField>()
                        val possibleTypes = linkedSetOf<Schema.ObjectType>()
                        val type: Schema.CompositeType = when (graphQLType) {
                            is GraphQLObjectType ->
                                ObjectTypeImpl(graphQLType.name, fields, possibleTypes)
                            is GraphQLInterfaceType ->
                                InterfaceTypeImpl(graphQLType.name, fields, possibleTypes)
                            else ->
                                UnionTypeImpl((graphQLType as GraphQLUnionType).name, fields, possibleTypes)
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
                val modelFields = compositeFields.getValue(modelType)
                graphQLType.fieldDefinitions.forEach { graphQLField ->
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
                        OutputFieldImpl(
                            fieldName = graphQLField.name,
                            containingType = modelType,
                            typeExpr = decodeOutputType(graphQLField.type),
                            arguments = arguments,
                        )
                    modelFields[modelField.fieldName] = modelField
                }
            }
    }

    private fun addTypeNameField(type: Schema.CompositeType) {
        val field =
            OutputFieldImpl(
                fieldName = "__typename",
                containingType = type,
                typeExpr = TypeExpr.Named.of(Schema.StringType, isNullable = false),
                arguments = Schema.NoArguments,
            )
        compositeFields.getValue(type)[field.fieldName] = field
    }

    private fun decodeOutputType(type: GraphQLOutputType): TypeExpr<Schema.OutputType> =
        decodeType(type) { typeName ->
            types.getValue(typeName) as Schema.OutputType
        }

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
            Value.Default.of(
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
): Value.Input? =
    if (value.isLiteral) {
        decodeLiteral(type, value.value as GraphQLValue<*>, variableValues, schema)
    } else {
        decodeExternal(type, value.value, variableValues, schema)
    }

internal fun decodeLiteral(
    type: GraphQLInputType,
    value: GraphQLValue<*>,
    variableValues: VariableBindings,
    schema: Schema,
): Value.Input? {
    if (value is VariableReference) {
        return if (variableValues.containsKey(value.name)) {
            variableValues.getValue(value.name)
        } else {
            Value.Variable.of(value.name)
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
            Value.InputList.of(
                typeExpr = decodeModelInputType(type.wrappedType as GraphQLInputType, schema),
                values = values.map {
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
            Value.Enum.of(
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
    decodeSupplied: (GraphQLInputType, String) -> Value.Input?,
    variableValues: VariableBindings,
    schema: Schema,
): Value.Input {
    val fields =
        buildMap<String, Value.Input?> {
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
    return Value.InputObject.of(
        type = schema.type(type.name) as Schema.InputObjectType,
        fields = fields,
    )
}

private fun decodeObjectLiteral(
    type: GraphQLInputObjectType,
    value: ObjectValue,
    variableValues: VariableBindings,
    schema: Schema,
): Value.Input {
    val suppliedFields = value.objectFields.associateBy { it.name }
    return decodeInputObjectFields(
        type = type,
        isFieldSupplied = { suppliedFields.containsKey(it) },
        decodeSupplied = { fieldType, fieldName ->
            decodeLiteral(fieldType, suppliedFields.getValue(fieldName).value, variableValues, schema)
        },
        variableValues = variableValues,
        schema = schema,
    )
}

private fun decodeExternal(
    type: GraphQLInputType,
    value: Any?,
    variableValues: VariableBindings,
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

private fun decodeObjectExternal(
    type: GraphQLInputObjectType,
    value: Any,
    variableValues: VariableBindings,
    schema: Schema,
): Value.Input {
    val valueMap = value as Map<*, *>
    return decodeInputObjectFields(
        type = type,
        isFieldSupplied = { valueMap.containsKey(it) },
        decodeSupplied = { fieldType, fieldName ->
            decodeExternal(fieldType, valueMap[fieldName], variableValues, schema)
        },
        variableValues = variableValues,
        schema = schema,
    )
}

private class EnumTypeImpl(
    override val typeName: String,
    override val values: Set<String>,
) : Schema.EnumType

private class ObjectTypeImpl(
    override val typeName: String,
    override val fields: Map<String, Schema.OutputField>,
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
