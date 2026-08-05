package model

import model.invariants.conformsToSchemaType

/**
 * A GraphQL semantic value.
 *
 * Implementations are mathematical values: equality is value equality over the properties exposed
 * by the interface. [Variable] equality is exact name equality.
 *
 * ### Invariant: schema-value-canonicality
 *
 * Every schema definition carried by a value is the canonical definition from the
 * [Assumptions.schema] under which that value is interpreted.
 */
sealed interface Value {
    sealed interface Input : Value

    sealed interface Output : Value

    sealed interface Typed : Value {
        val type: Schema.Type
    }

    sealed interface Simple : Input, Output, EngineResult {
        val type: Schema.SimpleType
    }

    /**
     * A value in the model's closed universe of built-in scalar leaf types: [Int], [Float],
     * [String], [Boolean], or [ID].
     */
    sealed interface Scalar : Simple {
        override val type: Schema.ScalarType
    }

    sealed interface Int : Scalar {
        override val type: Schema.IntType
            get() = Schema.IntType

        val intValue: kotlin.Int

        companion object {
            /**
             * ### Invariant: int-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(value: kotlin.Int): Int = IntValueImpl(value)
        }
    }

    sealed interface Float : Scalar {
        override val type: Schema.FloatType
            get() = Schema.FloatType

        /**
         * ### Invariant: schema-finite-float
         *
         * This value is finite; NaN and positive or negative infinity are excluded.
         */
        val floatValue: Double

        companion object {
            /**
             * ### Invariant: float-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(value: Double): Float {
                require(value.isFinite()) { "GraphQL Float values must be finite" }
                return FloatValueImpl(value)
            }
        }
    }

    sealed interface String : Scalar {
        override val type: Schema.StringType
            get() = Schema.StringType

        val stringValue: kotlin.String

        companion object {
            /**
             * ### Invariant: string-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(value: kotlin.String): String = StringValueImpl(value)
        }
    }

    sealed interface Boolean : Scalar {
        override val type: Schema.BooleanType
            get() = Schema.BooleanType

        val booleanValue: kotlin.Boolean

        companion object {
            /**
             * ### Invariant: boolean-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(value: kotlin.Boolean): Boolean = BooleanValueImpl(value)
        }
    }

    sealed interface ID : Scalar {
        override val type: Schema.IDType
            get() = Schema.IDType

        val idValue: kotlin.String

        companion object {
            /**
             * ### Invariant: id-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(value: kotlin.String): ID = IDValueImpl(value)
        }
    }

    sealed interface Enum : Simple {
        /**
         * ### Invariant: schema-enum-membership
         *
         * [type] is the canonical enum definition through which this value was constructed, and
         * [enumValue] is a member of [type.values].
         */
        override val type: Schema.EnumType
        val enumValue: kotlin.String

        companion object {
            /**
             * ### Invariant: enum-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                type: Schema.EnumType,
                value: kotlin.String,
            ): Enum {
                require(value in type.values) { "$value is not a value of ${type.typeName}" }
                return EnumValueImpl(type, value)
            }
        }
    }

    sealed interface List<out T : Schema.Type> : Value {
        val typeExpr: TypeExpr<T>
        val values: kotlin.collections.List<Value?>
    }

    /** An input list with structural equality over its type expression and elements. */
    sealed interface InputList : Input, List<Schema.InputType> {
        override val values: kotlin.collections.List<Input?>

        companion object {
            /**
             * ### Invariant: input-list-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                typeExpr: TypeExpr<Schema.InputType>,
                values: kotlin.collections.List<Input?>,
            ): InputList {
                require(values.all { it.conformsToSchemaType(typeExpr) }) {
                    "Input list element does not conform to $typeExpr"
                }
                return InputListValueImpl(typeExpr, values)
            }
        }
    }

    sealed interface OutputList : Output, List<Schema.OutputType> {
        override val values: kotlin.collections.List<Output?>

        companion object {
            /**
             * ### Invariant: output-list-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                typeExpr: TypeExpr<Schema.OutputType>,
                values: kotlin.collections.List<Output?>,
            ): OutputList {
                require(values.all { it.conformsToSchemaType(typeExpr) }) {
                    "Output list element does not conform to $typeExpr"
                }
                return OutputListValueImpl(typeExpr, values)
            }
        }
    }

    /**
     * A value shaped like a GraphQL input object.
     *
     * ### Invariant: input-like-value-coherence
     *
     * [type] is the canonical input-object-like definition whose fields [fieldValues] inhabit.
     * `fieldValues.containingType == type`.
     *
     * Implementations narrow [type] to match their corresponding definition category. This common
     * interface is not itself a GraphQL [Value], because [Arguments] is a schema-synthetic tuple.
     */
    sealed interface InputLike {
        val type: Schema.InputObjectLike
        val fieldValues: Fields<Schema.InputObjectLike, Input>
    }

    /** An input object whose fields may contain nested [Variable] instances. */
    sealed interface InputObject : Input, Typed, InputLike {
        override val type: Schema.InputObjectType
        override val fieldValues: Fields<Schema.InputObjectType, Input>

        companion object {
            /**
             * ### Invariant: input-object-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             * Declared defaults are materialized for fields absent from [fields].
             */
            fun of(
                type: Schema.InputObjectType,
                fields: Map<kotlin.String, Any?>,
            ): InputObject =
                InputObjectValueImpl(
                    type = type,
                    fieldValues =
                        FieldValuesImpl(
                            type,
                            coerceInputLikeFields(type, fields),
                        ),
                )
        }
    }

    /**
     * The values supplied for one output field's complete argument definition.
     *
     * A value may contain nested [Variable] instances when it belongs to a [Key] used outside a
     * [Value.Object] or [EngineResult.Object].
     */
    sealed interface Arguments : InputLike {
        override val type: Schema.FieldArguments
        override val fieldValues: Fields<Schema.FieldArguments, Input>

        companion object {
            /**
             * ### Invariant: arguments-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             * Declared defaults are materialized for arguments absent from [fields].
             */
            fun of(
                field: Schema.OutputField,
                fields: Map<kotlin.String, Any?>,
            ): Arguments =
                ArgumentsValueImpl(
                    type = field.arguments,
                    fieldValues =
                        FieldValuesImpl(
                            field.arguments,
                            coerceInputLikeFields(
                                field.arguments,
                                fields,
                            ),
                        ),
                )
        }
    }

    /**
     * One alias-free output-field coordinate consisting of a canonical field and its arguments.
     *
     * ### Invariant: object-key-argument-definition
     *
     * `arguments.type == field.arguments`.
     *
     * Equality is structural over [field] and [arguments], using canonical schema equality.
     */
    sealed interface Key {
        val field: Schema.OutputField
        val arguments: Arguments

        companion object {
            /**
             * ### Invariant: map-key-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                field: Schema.OutputField,
                arguments: Map<kotlin.String, Any?>,
            ): Key = of(field, Arguments.of(field, arguments))

            /**
             * ### Invariant: arguments-key-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                field: Schema.OutputField,
                arguments: Arguments,
            ): Key {
                require(arguments.type == field.arguments) {
                    "Key arguments do not belong to its output field"
                }
                return ObjectKeyImpl(field, arguments)
            }
        }
    }

    /**
     * A possibly partial object output.
     *
     * ### Invariant: object-value-owner
     *
     * `fieldValues.containingType == type`. Every present [Key] carries a field owned by [type] and
     * arguments containing no unresolved [Variable].
     */
    sealed interface Object : Output, Typed {
        override val type: Schema.ObjectType
        val fieldValues: ObjectFields

        companion object {
            /**
             * ### Invariant: object-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                type: Schema.ObjectType,
                fields: Map<Key, Output?> = emptyMap(),
            ): Object {
                fields.forEach { (key, value) ->
                    require(value.conformsToSchemaType(key.field.typeExpr)) {
                        "${type.typeName}/${key.field.fieldName} value does not conform to " +
                            key.field.typeExpr
                    }
                }
                return ObjectValueImpl(
                    type = type,
                    fieldValues = ObjectFieldValuesImpl(type, fields),
                )
            }
        }
    }

    /**
     * A finite map from exact output-field coordinates to values.
     *
     * ### Invariant: object-field-values-owner
     *
     * [containingType] is the concrete object type whose fields these values inhabit. Every present
     * [Key] carries a [Schema.ObjectField] owned by [containingType] and arguments containing no
     * unresolved [Variable].
     */
    sealed interface ObjectFields : Map<Key, Output?> {
        val containingType: Schema.ObjectType

        /** @throws MissingFieldException when [key] is not present */
        override operator fun get(key: Key): Output?

        /** @throws MissingFieldException when [key] is not present */
        fun getValue(key: Key): Output?
    }

    /**
     * A map from field names to values.
     *
     * ### Invariant: field-values-owner
     *
     * [containingType] is the canonical [Schema.Type] or [Schema.FieldArguments] definition whose
     * fields these values inhabit, and every present key names one of its fields.
     *
     * Unlike an ordinary [Map], [get] and [getValue] throw [MissingFieldException] when the
     * requested field does not exist or has not been set.
     */
    sealed interface Fields<out T : Any, out V : Value> : Map<kotlin.String, V?> {
        val containingType: T

        /** @throws MissingFieldException when [key] is not present */
        override operator fun get(key: kotlin.String): V?

        /** @throws MissingFieldException when [key] is not present */
        fun getValue(key: kotlin.String): V?
    }

    /**
     * A symbolic reference to an execution variable.
     *
     * Two variables are equal exactly when they have the same name.
     */
    sealed interface Variable : Input {
        val variableName: kotlin.String

        companion object {
            /**
             * ### Invariant: variable-value-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(variableName: kotlin.String): Variable = VariableValueImpl(variableName)
        }
    }

    /**
     * The bottom value of the GraphQL value hierarchy.
     *
     * Error metadata, paths, and multiplicity are intentionally collapsed into this singleton.
     */
    data object Error :
        Int,
        Float,
        String,
        Boolean,
        ID,
        Enum,
        InputObject,
        Object,
        Variable {
        override val type: Nothing
            get() = unsupported()

        override val intValue: kotlin.Int
            get() = unsupported()

        override val floatValue: Double
            get() = unsupported()

        override val stringValue: kotlin.String
            get() = unsupported()

        override val booleanValue: kotlin.Boolean
            get() = unsupported()

        override val idValue: kotlin.String
            get() = unsupported()

        override val enumValue: kotlin.String
            get() = unsupported()

        override val fieldValues: Nothing
            get() = unsupported()

        override val variableName: kotlin.String
            get() = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("Value.Error has no observable properties")
    }

    /**
     * An optional, fully coerced semantic default.
     *
     * ### Invariant: schema-default-value-conformance
     *
     * [Absent] means that no default is declared. [Present.value] may be null, denoting an explicit
     * GraphQL null; absence and explicit null are distinct. When attached to a field or argument,
     * [Present] is valid for its declaring [TypeExpr]. It contains neither [Variable] nor [Error].
     *
     * Default values use structural equality.
     */
    sealed interface Default {
        data object Absent : Default

        sealed interface Present : Default {
            val value: Input?
        }

        companion object {
            fun of(value: Input?): Present = PresentDefaultValueImpl(value)
        }
    }
}

private data class IntValueImpl(
    override val intValue: Int,
) : Value.Int

private data class FloatValueImpl(
    override val floatValue: Double,
) : Value.Float

private data class StringValueImpl(
    override val stringValue: String,
) : Value.String

private data class BooleanValueImpl(
    override val booleanValue: Boolean,
) : Value.Boolean

private data class IDValueImpl(
    override val idValue: String,
) : Value.ID

private data class EnumValueImpl(
    override val type: Schema.EnumType,
    override val enumValue: String,
) : Value.Enum

private data class InputListValueImpl(
    override val typeExpr: TypeExpr<Schema.InputType>,
    override val values: kotlin.collections.List<Value.Input?>,
) : Value.InputList

private data class OutputListValueImpl(
    override val typeExpr: TypeExpr<Schema.OutputType>,
    override val values: kotlin.collections.List<Value.Output?>,
) : Value.OutputList

private data class ObjectValueImpl(
    override val type: Schema.ObjectType,
    override val fieldValues: Value.ObjectFields,
) : Value.Object

private data class InputObjectValueImpl(
    override val type: Schema.InputObjectType,
    override val fieldValues: Value.Fields<Schema.InputObjectType, Value.Input>,
) : Value.InputObject

private data class ArgumentsValueImpl(
    override val type: Schema.FieldArguments,
    override val fieldValues: Value.Fields<Schema.FieldArguments, Value.Input>,
) : Value.Arguments

private data class VariableValueImpl(
    override val variableName: String,
) : Value.Variable

private data class ObjectKeyImpl(
    override val field: Schema.OutputField,
    override val arguments: Value.Arguments,
) : Value.Key

private data class PresentDefaultValueImpl(
    override val value: Value.Input?,
) : Value.Default.Present

private class ObjectFieldValuesImpl(
    override val containingType: Schema.ObjectType,
    private val backingMap: Map<Value.Key, Value.Output?>,
) : Value.ObjectFields,
    Map<Value.Key, Value.Output?> by backingMap {
    init {
        require(backingMap.keys.all { it.field.containingType == containingType }) {
            val foreignFields =
                backingMap.keys
                    .filter { it.field.containingType != containingType }
                    .map { "${it.field.containingType.typeName}/${it.field.fieldName}" }
            "${containingType.typeName} cannot contain output fields " +
                foreignFields.sorted().joinToString()
        }
        require(
            backingMap.keys.none { key ->
                key.arguments.fieldValues.values.any { it.containsVariableValue() }
            },
        ) {
            "${containingType.typeName} object-value keys cannot contain unresolved variables"
        }
    }

    override operator fun get(key: Value.Key): Value.Output? = getValue(key)

    override fun getValue(key: Value.Key): Value.Output? {
        if (!backingMap.containsKey(key)) {
            throw MissingFieldException(containingType.typeName, key.field.fieldName)
        }
        return backingMap[key]
    }

    override fun equals(other: Any?): Boolean =
        other is Value.ObjectFields &&
            containingType == other.containingType &&
            entries == other.entries

    override fun hashCode(): Int = 31 * containingType.hashCode() + backingMap.hashCode()

    override fun toString(): String = backingMap.toString()
}

private class FieldValuesImpl<out T : Any, out V : Value>(
    override val containingType: T,
    private val backingMap: Map<String, V?>,
) : Value.Fields<T, V>,
    Map<String, V?> by backingMap {
    override operator fun get(key: String): V? = getValue(key)

    override fun getValue(key: String): V? {
        if (!backingMap.containsKey(key)) {
            val typeName =
                when (containingType) {
                    is Schema.Type -> containingType.typeName
                    is Schema.FieldArguments -> "\$ARGUMENTS"
                    else -> error("Unexpected field-value definition: $containingType")
                }
            throw MissingFieldException(typeName, key)
        }
        return backingMap[key]
    }

    override fun equals(other: Any?): Boolean =
        other is Value.Fields<*, *> &&
            containingType == other.containingType &&
            entries == other.entries

    override fun hashCode(): Int = 31 * containingType.hashCode() + backingMap.hashCode()

    override fun toString(): String = backingMap.toString()
}

private fun coerceInputLikeFields(
    type: Schema.InputObjectLike,
    fields: Map<String, Any?>,
): Map<String, Value.Input?> {
    val suppliedFields =
        fields.mapValues { (fieldName, value) ->
            val field =
                type.fields[fieldName]
                    ?: throw ClassCastException()
            coerceInputValue(field.typeExpr, value)
        }

    return buildMap {
        type.fields.values.forEach { field ->
            val defaultValue = field.defaultValue
            if (defaultValue is Value.Default.Present) {
                put(field.name, defaultValue.value)
            }
        }
        putAll(suppliedFields)
    }
}

private fun coerceInputValue(
    typeExpr: TypeExpr<Schema.InputType>,
    value: Any?,
): Value.Input? {
    if (value == null) {
        if (!typeExpr.isNullable) throw ClassCastException()
        return null
    }
    if (value == Value.Error) return Value.Error
    if (value is Value.Variable) return value

    return when (typeExpr) {
        is TypeExpr.Named -> coerceNamedInputValue(typeExpr.baseType, value)
        is TypeExpr.List -> {
            val elements =
                when (value) {
                    is Value.InputList -> value.values
                    is kotlin.collections.List<*> -> value
                    else -> throw ClassCastException()
                }
            Value.InputList.of(
                typeExpr = typeExpr.elementType,
                values = elements.map { coerceInputValue(typeExpr.elementType, it) },
            )
        }
    }
}

private fun coerceNamedInputValue(
    type: Schema.InputType,
    value: Any,
): Value.Input =
    when (type) {
        Schema.IntType ->
            when (value) {
                is Value.Int -> value
                is Int -> Value.Int.of(value)
                else -> throw ClassCastException()
            }

        Schema.FloatType ->
            when (value) {
                is Value.Float -> value
                is Double -> Value.Float.of(value)
                else -> throw ClassCastException()
            }

        Schema.StringType ->
            when (value) {
                is Value.String -> value
                is String -> Value.String.of(value)
                else -> throw ClassCastException()
            }

        Schema.BooleanType ->
            when (value) {
                is Value.Boolean -> value
                is Boolean -> Value.Boolean.of(value)
                else -> throw ClassCastException()
            }

        Schema.IDType ->
            when (value) {
                is Value.ID -> value
                is String -> Value.ID.of(value)
                else -> throw ClassCastException()
            }

        is Schema.EnumType ->
            when (value) {
                is Value.Enum ->
                    if (value.type == type) value else throw ClassCastException()
                is String -> Value.Enum.of(type, value)
                else -> throw ClassCastException()
            }

        is Schema.InputObjectType ->
            when (value) {
                is Value.InputObject ->
                    if (value.type == type) value else throw ClassCastException()
                is Map<*, *> -> Value.InputObject.of(type, value.toStringKeyedMap())
                else -> throw ClassCastException()
            }
    }

private fun Map<*, *>.toStringKeyedMap(): Map<String, Any?> =
    entries.associate { (key, value) ->
        if (key !is String) throw ClassCastException()
        key to value
    }

internal fun Value.Input?.containsVariableValue(): Boolean =
    when {
        this == null || this == Value.Error -> false
        this is Value.Variable -> true
        this is Value.InputList -> values.any { it.containsVariableValue() }
        this is Value.InputObject -> fieldValues.values.any { it.containsVariableValue() }
        else -> false
    }
