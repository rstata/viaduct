package model

import model.invariants.conformsToSchemaType

/**
 * One step in an exact path through an engine-result tree.
 *
 * A [Value.GroundKey] selects an object field, while a [Value.ListIndex] selects a list element.
 * Equality is structural within each variant.
 */
sealed interface PathComponent

/**
 * Returns this exact OER path as an object-key-only selection path.
 *
 * A null path or any path containing a [Value.ListIndex] has no corresponding selection path and
 * yields null.
 */
fun kotlin.collections.List<PathComponent>?.toSelectionPath():
    kotlin.collections.List<Value.GroundKey>? =
    this?.map { component -> component as? Value.GroundKey ?: return null }

/**
 * A ground GraphQL semantic value.
 *
 * Implementations are mathematical values: equality is value equality over the properties exposed
 * by the interface. No implementation contains a [Variable]. Variables are nested here for
 * namespacing but inhabit [OpenValue] rather than [Value]. [Variable.Template] equality is
 * structural over its name and defining field; [Variable.Stamped] equality additionally
 * distinguishes its occurrence.
 *
 * ### Invariant: schema-value-canonicality
 *
 * Every schema definition carried by a value is the canonical definition from the
 * [Assumptions.schema] under which that value is interpreted.
 */
sealed interface Value {
    /**
     * A ground GraphQL input value or schema-synthetic field-argument tuple.
     *
     * [Input] values are members of [Value], while [Arguments] is an input-like tuple rather than a
     * GraphQL value.
     */
    sealed interface InputLike

    sealed interface Input : Value, InputLike, OpenValue

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
     * ### Invariant: input-object-like-value-coherence
     *
     * [type] is the canonical input-object-like definition whose fields [fieldValues] inhabit.
     * `fieldValues.containingType == type`.
     *
     * Implementations narrow [type] to match their corresponding definition category. This common
     * interface is not itself a GraphQL [Value], because [Arguments] is a schema-synthetic tuple.
     */
    sealed interface InputObjectLike : InputLike {
        val type: Schema.InputObjectLike
        val fieldValues: Fields<Schema.InputObjectLike, Input>

    }

    /** A ground input object. */
    sealed interface InputObject : Input, Typed, InputObjectLike {
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
     * This tuple is ground and inspectable. [OpenArguments] represents a tuple that may contain
     * variables. Equality is structural over its field values. Occurrence identity belongs to
     * [GroundKey.Stamped], not the grounded argument value.
     */
    sealed interface Arguments : InputObjectLike, OpenArguments {
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
            ): Arguments {
                val arguments = OpenArguments.of(field, fields)
                require(arguments is Arguments) {
                    "Ground arguments cannot contain variables"
                }
                return arguments
            }
        }
    }

    /**
     * One alias-free output-field coordinate consisting of a canonical field and its arguments.
     *
     * ### Invariant: key-argument-definition
     *
     * `arguments.type == field.arguments`.
     *
     * ### Invariant: object-key-field-classification
     *
     * A key's [field] is a [Schema.ObjectField] exactly when the key is an [ObjectKey].
     *
     * Ordinary-key equality is structural over [field] and [arguments], using canonical schema
     * equality. [GroundKey.Stamped] additionally includes its opaque selection occurrence stamp;
     * callers that need resolver-visible identity must explicitly project it to an ordinary key.
     */
    sealed interface Key {
        val field: Schema.OutputField
        val arguments: OpenArguments

        companion object {
            /**
             * ### Invariant: map-key-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                field: Schema.OutputField,
                arguments: Map<kotlin.String, Any?>,
            ): Key = of(field, OpenArguments.of(field, arguments))

            /** Constructs the precise key category for a field on a concrete object type. */
            fun of(
                field: Schema.ObjectField,
                arguments: Map<kotlin.String, Any?>,
            ): ObjectKey = ObjectKey.of(field, arguments)

            /**
             * ### Invariant: arguments-key-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                field: Schema.OutputField,
                arguments: OpenArguments,
            ): Key {
                require(arguments.type == field.arguments) {
                    "Key arguments do not belong to its output field"
                }
                return when (field) {
                    is Schema.ObjectField -> ObjectKey.of(field, arguments)
                    else -> KeyImpl(field, arguments)
                }
            }

            /** Constructs the precise key category for a field on a concrete object type. */
            fun of(
                field: Schema.ObjectField,
                arguments: OpenArguments,
            ): ObjectKey = ObjectKey.of(field, arguments)

            /** Constructs the precise ground key category. */
            fun of(
                field: Schema.ObjectField,
                arguments: Arguments,
            ): GroundKey = GroundKey.of(field, arguments)
        }
    }

    /**
     * A selection-only key marking one component of a stamped path-variable's provider path.
     *
     * The marker remains distinct from [ObjectKey] even when [field] belongs to a concrete object
     * type. [model.mergeWithVariables] is the explicit boundary that converts it to a ground key
     * and reports a binding when the path terminates at this component.
     */
    sealed interface VariableKey : Key {
        val variableDefinedByThisKey: Variable.Stamped

        companion object {
            fun of(
                key: Key,
                variableDefinedByThisKey: Variable.Stamped,
            ): VariableKey =
                VariableKeyImpl(
                    field = key.field,
                    arguments = key.arguments,
                    variableDefinedByThisKey = variableDefinedByThisKey,
                )
        }
    }

    /**
     * A key whose field belongs to a concrete object type.
     *
     * Every instance carries a [Schema.ObjectField] and [OpenArguments]. Ordinary instances use
     * structural key equality; [GroundKey.Stamped] additionally retains occurrence identity.
     */
    sealed interface ObjectKey : Key {
        override val field: Schema.ObjectField
        override val arguments: OpenArguments

        companion object {
            fun of(
                field: Schema.ObjectField,
                arguments: Map<kotlin.String, Any?>,
            ): ObjectKey = of(field, OpenArguments.of(field, arguments))

            fun of(
                field: Schema.ObjectField,
                arguments: OpenArguments,
            ): ObjectKey {
                require(arguments.type == field.arguments) {
                    "Key arguments do not belong to its output field"
                }
                return if (arguments is Arguments) {
                    GroundKeyImpl(field, arguments)
                } else {
                    ObjectKeyImpl(field, arguments)
                }
            }
        }
    }

    /**
     * A concrete-object key whose arguments are ground and which can therefore select an OER field.
     */
    sealed interface GroundKey : ObjectKey, PathComponent {
        override val arguments: Arguments

        /**
         * A ground key produced from a variable-bearing source selection.
         *
         * [selectionStamp] identifies the variable-bearing source selection that was grounded. It
         * distinguishes different source selections even when their grounded arguments agree.
         */
        sealed interface Stamped : GroundKey {
            val selectionStamp: SelectionStamp

            companion object {
                fun of(
                    selectionStamp: SelectionStamp,
                    field: Schema.ObjectField,
                    arguments: Arguments,
                ): Stamped {
                    require(arguments.type == field.arguments) {
                        "Ground arguments do not belong to the stamped selection field"
                    }
                    return StampedGroundKeyImpl(
                        field = field,
                        arguments = arguments,
                        selectionStamp = selectionStamp,
                    )
                }
            }
        }

        companion object {
            fun of(
                field: Schema.ObjectField,
                arguments: Map<kotlin.String, Any?>,
            ): GroundKey = of(field, Arguments.of(field, arguments))

            fun of(
                field: Schema.ObjectField,
                arguments: Arguments,
            ): GroundKey {
                require(arguments.type == field.arguments) {
                    "Key arguments do not belong to its output field"
                }
                return GroundKeyImpl(field, arguments)
            }
        }
    }

    /** A non-negative position selecting one element of an engine-result list. */
    sealed interface ListIndex : PathComponent {
        val index: kotlin.Int

        companion object {
            fun of(index: kotlin.Int): ListIndex {
                require(index >= 0) { "List index must be non-negative" }
                return ListIndexImpl(index)
            }
        }
    }

    /**
     * A possibly partial object output.
     *
     * ### Invariant: object-value-owner
     *
     * `fieldValues.containingType == type`. Every present [GroundKey] carries a field owned by
     * [type]. Object values are partial; resolver behavior is responsible for supplying passive
     * fields, including canonical `__typename`.
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
                fields: Map<GroundKey, Output?> = emptyMap(),
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
     * [GroundKey] carries a field owned by [containingType].
     */
    sealed interface ObjectFields : Map<GroundKey, Output?> {
        val containingType: Schema.ObjectType

        /** @throws MissingFieldException when [key] is not present */
        override operator fun get(key: GroundKey): Output?

        /** @throws MissingFieldException when [key] is not present */
        fun getValue(key: GroundKey): Output?
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
     * Identifier of an execution variable.
     *
     * Each field with a resolver can define variables from one of that field's arguments or from a
     * value resolved along a path in its object fragment. The registry contains [Template]
     * variables associated with the resolver. During resolution, those templates are [Stamped] as
     * they enter occurrence-specific resolution structures. A resolver can occur multiple times in
     * one resolution; stamping distinguishes the variable instances belonging to those
     * occurrences.
     */
    sealed interface Variable : OpenValue {
        val field: Schema.ObjectField
        val variableName: kotlin.String

        /**
         * A variable in the [ResolverRegistry]. A template is stamped during resolution to create
         * a distinct variable for one occurrence of its defining resolver.
         *
         * Exact [EngineResult.Object] paths stamp templates. Stamps are otherwise opaque and
         * uninterpreted except for equality; paths provide uniqueness and useful diagnostics
         * without becoming an observable dimension of [Stamped].
         */
        sealed interface Template : Variable {
            /**
             * Returns the variable for this template at [path].
             *
             * Equal templates stamped with equal paths yield equal results. A result is unequal to
             * every other variable except an equal template stamped with the same path.
             *
             * ### Invariant: stamped-variable-value-factory-schema-conformance
             *
             * Every [PathComponent] in [path] belongs to the template's reasoning world, and every
             * result satisfies `result.conformsToSchema()` in that world.
             */
            fun stamp(path: kotlin.collections.List<PathComponent>): Stamped

            /** Returns this variable template at one variable-bearing source selection. */
            fun stamp(selectionStamp: SelectionStamp): SelectionStamped
        }

        /** An opaque occurrence-specific variable created by stamping a [Template]. */
        sealed interface Stamped : Variable

        /** A variable use identified by its source selection and defining resolver occurrence. */
        sealed interface SelectionStamped : Stamped {
            val selectionStamp: SelectionStamp
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
            ): Template = TemplateVariableValueImpl(variableName, field)
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
    override val type: Schema.InputObjectType,
    override val fieldValues: Value.Fields<Schema.InputObjectType, Value.Input>,
) : Value.InputObject

private data class ArgumentsValueImpl(
    override val type: Schema.FieldArguments,
    override val fieldValues: Value.Fields<Schema.FieldArguments, Value.Input>,
) : Value.Arguments

private data class TemplateVariableValueImpl(
    override val variableName: String,
    override val field: Schema.ObjectField,
) : Value.Variable.Template {
    override fun stamp(
        path: kotlin.collections.List<PathComponent>,
    ): Value.Variable.Stamped =
        StampedVariableValueImpl(variableName, field, path)

    override fun stamp(
        selectionStamp: SelectionStamp,
    ): Value.Variable.SelectionStamped =
        SelectionStampedVariableValueImpl(variableName, field, selectionStamp)

    override fun toString(): String =
        "Variable.Template(" +
            "name=$variableName, " +
            "field=${field.containingType.typeName}/${field.fieldName}" +
            ")"
}

private data class StampedVariableValueImpl(
    override val variableName: String,
    override val field: Schema.ObjectField,
    private val path: kotlin.collections.List<PathComponent>,
) : Value.Variable.Stamped {
    override fun toString(): String =
        "Variable.Stamped(" +
            "name=$variableName, " +
            "field=${field.containingType.typeName}/${field.fieldName}, " +
            "path=${path.renderVariablePath()}" +
            ")"
}

private data class SelectionStampedVariableValueImpl(
    override val variableName: String,
    override val field: Schema.ObjectField,
    override val selectionStamp: SelectionStamp,
) : Value.Variable.SelectionStamped {
    override fun toString(): String =
        "Variable.SelectionStamped(" +
            "name=$variableName, " +
            "field=${field.containingType.typeName}/${field.fieldName}, " +
            "resolverPath=${selectionStamp.resolverPath.renderVariablePath()}" +
            ")"
}

private fun kotlin.collections.List<PathComponent>.renderVariablePath(): String =
    joinToString(prefix = "[", postfix = "]") { component ->
        when (component) {
            is Value.GroundKey ->
                "${component.field.containingType.typeName}/${component.field.fieldName}" +
                    component.arguments.fieldValues
                        .takeIf { arguments -> arguments.isNotEmpty() }
                        ?.let { arguments -> "($arguments)" }
                        .orEmpty()
            is Value.ListIndex -> "index=${component.index}"
        }
    }

private data class KeyImpl(
    override val field: Schema.OutputField,
    override val arguments: OpenArguments,
) : Value.Key

private data class VariableKeyImpl(
    override val field: Schema.OutputField,
    override val arguments: OpenArguments,
    override val variableDefinedByThisKey: Value.Variable.Stamped,
) : Value.VariableKey

private data class ObjectKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: OpenArguments,
) : Value.ObjectKey

private data class GroundKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: Value.Arguments,
) : Value.GroundKey

private data class StampedGroundKeyImpl(
    override val field: Schema.ObjectField,
    override val arguments: Value.Arguments,
    override val selectionStamp: SelectionStamp,
) : Value.GroundKey.Stamped

private data class ListIndexImpl(
    override val index: Int,
) : Value.ListIndex

private data class PresentDefaultValueImpl(
    override val value: Value.Input?,
) : Value.Default.Present

private class ObjectFieldValuesImpl(
    override val containingType: Schema.ObjectType,
    private val backingMap: Map<Value.GroundKey, Value.Output?>,
) : Value.ObjectFields,
    Map<Value.GroundKey, Value.Output?> by backingMap {
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

    override operator fun get(key: Value.GroundKey): Value.Output? = getValue(key)

    override fun getValue(key: Value.GroundKey): Value.Output? {
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

internal fun argumentsOfGround(
    type: Schema.FieldArguments,
    fields: Map<String, Value.Input?>,
): Value.Arguments {
    require(
        fields.all { (name, value) ->
            val field = type.fields[name] ?: return@all false
            value.conformsToSchemaType(field.typeExpr)
        },
    ) {
        "Ground argument values do not conform to their field definition"
    }
    return ArgumentsValueImpl(
        type = type,
        fieldValues = FieldValuesImpl(type, fields),
    )
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
