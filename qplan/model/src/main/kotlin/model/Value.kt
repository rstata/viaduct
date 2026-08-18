package model

import model.invariants.conformsToSchemaType

/**
 * A ground GraphQL semantic value.
 *
 * Implementations are mathematical values: equality is value equality over the properties exposed
 * by the interface. No implementation contains a [Variable]. Variables are nested here for
 * namespacing but inhabit [OpenValue] rather than [Value]. Template-variable equality is structural
 * over its name and defining field; stamped-variable equality additionally distinguishes its
 * occurrence.
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

    sealed interface Simple : Input, Output {
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
             * Every result recursively conforms to the supplied element type expression.
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

    /** A ground input object. */
    sealed interface InputObject : Input {
        val fieldValues: Map<kotlin.String, Input?>

        companion object {
            /**
             * ### Invariant: input-object-value-factory-schema-conformance
             *
             * Every result recursively conforms to [type].
             * Declared defaults are materialized for fields absent from [fields].
             */
            fun of(
                type: Schema.InputObjectType,
                fields: Map<kotlin.String, Any?>,
            ): InputObject =
                InputObjectValueImpl(
                    fieldValues = coerceInputLikeFields(type, fields),
                )
        }
    }

    /**
     * The values supplied for one output field's complete argument definition.
     *
     * This tuple is ground and inspectable. [OpenArguments] represents a tuple that may contain
     * variables. Equality is structural over its field values. Occurrence identity belongs to
     * [ObjectEngineResult.Key.stamp], not the grounded argument value.
     */
    sealed interface Arguments : OpenArguments.Ground {
        val fieldValues: Map<kotlin.String, Input?>

        companion object {
            /**
             * ### Invariant: arguments-value-factory-schema-conformance
             *
             * Every result recursively conforms to [field]'s argument definition.
             * Declared defaults are materialized for arguments absent from [fields].
             */
            fun of(
                field: Schema.OutputField,
                fields: Map<kotlin.String, Any?>,
            ): Arguments {
                val arguments = OpenArguments.of(field, fields)
                require(arguments is Arguments) {
                    "Ground arguments cannot contain variables or errors"
                }
                return arguments
            }
        }
    }

    /**
     * A possibly partial object output.
     *
     * ### Invariant: object-value-owner
     *
     * `fieldValues.containingType == type`. Every present [ObjectEngineResult.GroundKey] carries a
     * field owned by [type]. Object values are partial; resolver behavior is responsible for
     * supplying passive fields, including canonical `__typename`.
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
                fields: Map<ObjectEngineResult.GroundKey, Output?> = emptyMap(),
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
     * A finite map from exact object-field coordinates to values.
     *
     * ### Invariant: object-field-values-owner
     *
     * [containingType] is the concrete object type whose fields these values inhabit. Every present
     * [ObjectEngineResult.GroundKey] carries a field owned by [containingType].
     */
    sealed interface ObjectFields : Map<ObjectEngineResult.GroundKey, Output?> {
        val containingType: Schema.ObjectType

        /** @throws MissingFieldException when [key] is not present */
        override operator fun get(key: ObjectEngineResult.GroundKey): Output?

        /** @throws MissingFieldException when [key] is not present */
        fun getValue(key: ObjectEngineResult.GroundKey): Output?
    }

    /**
     * Identifier of an execution variable.
     *
     * Each field with a resolver can define variables from one of that field's arguments or from a
     * value resolved along a path in its object fragment. The registry contains variable templates
     * associated with the resolver. During resolution, those templates are stamped as they enter
     * occurrence-specific resolution structures. A resolver can occur multiple times in one
     * resolution; stamping distinguishes the variable instances belonging to those occurrences.
     */
    sealed interface Variable : OpenValue {
        val field: Schema.ObjectField
        val variableName: kotlin.String

        /** Whether this is the registry template rather than an occurrence-specific variable. */
        val isTemplate: kotlin.Boolean

        /** The occurrence stamp, or null for a registry template. */
        val stamp: Stamp.Occurrence?

        val isStamped: kotlin.Boolean
            get() = stamp != null

        /**
         * Returns this variable template at [path].
         *
         * Equal templates stamped with equal paths yield equal results. A result is unequal to
         * every other variable except an equal template stamped with the same path.
         *
         * ### Invariant: stamped-variable-value-factory-schema-conformance
         *
         * Every [PathComponent] in [path] belongs to the template's reasoning world, and every
         * result satisfies `result.conformsToSchema()` in that world.
         */
        fun stamp(path: kotlin.collections.List<PathComponent>): Variable {
            require(isTemplate) { "Only variable templates can be stamped" }
            return OccurrenceVariableValueImpl(
                variableName,
                field,
                Stamp.Occurrence.of(path),
            )
        }

        /** Returns this variable template at one variable-bearing source selection. */
        fun stamp(stamp: Stamp.Occurrence): Variable {
            require(isTemplate) { "Only variable templates can be stamped" }
            return OccurrenceVariableValueImpl(variableName, field, stamp)
        }

        companion object {
            /**
             * Returns the template named [variableName] defined by [field]. Equal arguments yield
             * equal templates.
             *
             * ### Invariant: variable-value-factory-schema-conformance
             *
             * [field] is the canonical field of the resolver defining this variable, and every
             * result satisfies `result.conformsToSchema()` in that reasoning world.
             */
            fun of(
                field: Schema.ObjectField,
                variableName: kotlin.String,
            ): Variable = TemplateVariableValueImpl(variableName, field)
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
        OpenValue {
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

/**
 * Returns the structural union of two nullable output values.
 *
 * The union is defined only for equal leaves, equal list shapes, and objects of the same type.
 */
fun Value.Output?.unionOutput(other: Value.Output?): Value.Output? {
    if (this == null) {
        require(other == null) { "Cannot union null and non-null output values" }
        return null
    }
    require(other != null) { "Cannot union null and non-null output values" }

    return when (this) {
        Value.Error -> {
            require(other == Value.Error) { "Cannot union error and non-error output values" }
            Value.Error
        }

        is Value.Simple -> {
            require(other is Value.Simple && this == other) {
                "Cannot union unequal simple output values"
            }
            this
        }

        is Value.Object -> {
            require(other is Value.Object && type == other.type) {
                "Cannot union object output values of different types"
            }
            val fields =
                (fieldValues.keys + other.fieldValues.keys).associateWith { groundKey ->
                    when {
                        groundKey !in fieldValues -> other.fieldValues.getValue(groundKey)
                        groundKey !in other.fieldValues -> fieldValues.getValue(groundKey)
                        else ->
                            fieldValues
                                .getValue(groundKey)
                                .unionOutput(other.fieldValues.getValue(groundKey))
                    }
                }
            Value.Object.of(type = type, fields = fields)
        }

        is Value.OutputList -> {
            require(other is Value.OutputList && typeExpr == other.typeExpr) {
                "Cannot union output lists of different types"
            }
            require(values.size == other.values.size) {
                "Cannot union output lists of different lengths"
            }
            Value.OutputList.of(
                typeExpr = typeExpr,
                values =
                    values.indices.map { index ->
                        values[index].unionOutput(other.values[index])
                    },
            )
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
    override val fieldValues: Map<String, Value.Input?>,
) : Value.InputObject

private data class ArgumentsValueImpl(
    override val fieldValues: Map<String, Value.Input?>,
) : Value.Arguments

private data class TemplateVariableValueImpl(
    override val variableName: String,
    override val field: Schema.ObjectField,
) : Value.Variable {
    override val isTemplate: Boolean
        get() = true

    override val stamp: Stamp.Occurrence?
        get() = null

    override fun toString(): String =
        "Variable.Template(" +
            "name=$variableName, " +
            "field=${field.containingType.typeName}/${field.fieldName}" +
            ")"
}

private data class OccurrenceVariableValueImpl(
    override val variableName: String,
    override val field: Schema.ObjectField,
    override val stamp: Stamp.Occurrence,
) : Value.Variable {
    override val isTemplate: Boolean
        get() = false

    override fun toString(): String =
        "Variable.Occurrence(" +
            "name=$variableName, " +
            "field=${field.containingType.typeName}/${field.fieldName}, " +
            "path=${stamp.resolverPath.renderVariablePath()}, " +
            "lineage=${stamp.occurrenceLineage.size}" +
            ")"
}

private fun kotlin.collections.List<PathComponent>.renderVariablePath(): String =
    joinToString(prefix = "[", postfix = "]") { component ->
        when (component) {
            is ObjectEngineResult.GroundKey ->
                "${component.field.containingType.typeName}/${component.field.fieldName}" +
                    when (val arguments = component.arguments) {
                        OpenArguments.Ground.Error -> "(error)"
                        is Value.Arguments ->
                            arguments.fieldValues
                                .takeIf { fields -> fields.isNotEmpty() }
                                ?.let { fields -> "($fields)" }
                                .orEmpty()
                    }
            is ListEngineResult.Index -> "index=${component.index}"
        }
    }

private data class PresentDefaultValueImpl(
    override val value: Value.Input?,
) : Value.Default.Present

private class ObjectFieldValuesImpl(
    override val containingType: Schema.ObjectType,
    private val backingMap: Map<ObjectEngineResult.GroundKey, Value.Output?>,
) : Value.ObjectFields,
    Map<ObjectEngineResult.GroundKey, Value.Output?> by backingMap {
    init {
        require(backingMap.keys.all { it.field.containingType == containingType }) {
            val foreignFields =
                backingMap.keys
                    .filter { it.field.containingType != containingType }
                    .map { "${it.field.containingType.typeName}/${it.field.fieldName}" }
            "${containingType.typeName} cannot contain output fields " +
                foreignFields.sorted().joinToString()
        }
    }

    override operator fun get(key: ObjectEngineResult.GroundKey): Value.Output? = getValue(key)

    override fun getValue(key: ObjectEngineResult.GroundKey): Value.Output? {
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

internal fun argumentsOfGround(
    type: Schema.FieldArguments,
    fields: Map<String, Value.Input?>,
): Value.Arguments {
    require(
        fields.all { (name, value) ->
            val field = type.fields[name] ?: return@all false
            !value.containsInputError() && value.conformsToSchemaType(field.typeExpr)
        },
    ) {
        "Ground argument values do not conform to their field definition"
    }
    return ArgumentsValueImpl(
        fieldValues = fields,
    )
}

internal fun Value.Input?.containsInputError(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.InputList -> values.any { value -> value.containsInputError() }
        is Value.InputObject ->
            fieldValues.values.any { value -> value.containsInputError() }
        else -> false
    }

internal fun coerceInputValue(
    typeExpr: TypeExpr<Schema.InputType>,
    value: Any?,
): Value.Input? {
    if (value == null) {
        if (!typeExpr.isNullable) throw ClassCastException()
        return null
    }
    if (value == Value.Error) return Value.Error

    return when (typeExpr) {
        is TypeExpr.Named -> coerceNamedInputValue(typeExpr.baseType, value)
        is TypeExpr.List -> {
            val elements =
                when (value) {
                    is Value.InputList -> value.values
                    is kotlin.collections.List<*> -> value
                    else -> listOf(value)
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
                    if (value.conformsToSchemaType(TypeExpr.Named.of(type))) {
                        value
                    } else {
                        throw ClassCastException()
                    }
                is Map<*, *> -> Value.InputObject.of(type, value.toStringKeyedMap())
                else -> throw ClassCastException()
            }
    }

private fun Map<*, *>.toStringKeyedMap(): Map<String, Any?> =
    entries.associate { (key, value) ->
        if (key !is String) throw ClassCastException()
        key to value
    }
