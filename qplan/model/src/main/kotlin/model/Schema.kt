package model

/**
 * A finite GraphQL schema view used as an input to the correctness model.
 *
 * Exactly one instance is supplied by [Assumptions.schema] for a reasoning world.
 * Dependency-injection composition scopes that schema binding as a singleton.
 *
 * ### Invariant: schema-canonical-definition-graph
 *
 * Definitions are canonical within this schema: each type name, field coordinate, input-field
 * coordinate, and argument coordinate identifies exactly one definition object. Definition
 * classes do not override `Any.equals` or `Any.hashCode`, so `==` is reference equality and two
 * definitions are equal exactly when they represent the same schema element. For every definition
 * `d` reachable from this schema, `type(d.typeName) == d` when `d` is a [Type]; the corresponding
 * owner map contains `d` by its declared name for nested definitions. Every [TypeExpr.baseType]
 * reachable from the schema is likewise the canonical result of [type].
 *
 * Construct every [Value] other than the schema-independent [ErrorValue], every [ArgumentsValue],
 * and every [ObjectKey] through this schema's factory methods. The one-schema world
 * stipulates that every definition supplied to those methods is canonical in this schema; the
 * factories do not revalidate that ownership. Other modeling domains may instead use names and
 * coordinates. Nested definitions navigate to their canonical owners through
 * [OutputField.containingType] and [InputLikeField.containingType]. Compare definitions with ordinary
 * `==`, `!=`, and collection equality operations. Only acyclic value objects such as [TypeExpr] and
 * [DefaultValue] add structural equality over their properties.
 *
 * ### Invariant: schema-supported-domain
 *
 * [query] is the canonical `Query` [ObjectType] and is always the query root. The only permitted
 * scalar definitions are the five [ScalarType] singletons [IntType], [FloatType], [StringType],
 * [BooleanType], and [IDType]; whenever one belongs to this schema, [type] returns that singleton.
 *
 * Type-extension declarations, their boundaries, and their provenance are not represented; their
 * merged effects are already present in the effective field maps. Directives, descriptions, source
 * locations, introspection other than `__typename`, custom scalars, mutations, and subscriptions
 * are outside the current model.
 * Collections exposed by the schema are finite mathematical maps and sets; their iteration order,
 * concrete implementation, and mutability are not modeled.
 */
interface Schema {
    companion object {
        /**
         * The unique argument definition for every output field that takes no arguments.
         *
         * ### Invariant: schema-no-arguments
         *
         * For every [OutputField] `f`, `f.arguments == NoArguments` exactly when `f` takes no
         * arguments.
         */
        @JvmField
        val NoArguments: FieldArguments = FieldArguments.empty()
    }

    /**
     * The canonical query root.
     *
     * ### Invariant: schema-query-root
     *
     * `query.typeName == "Query"` and `type("Query") == query`.
     */
    val query: ObjectType

    /**
     * Returns the canonical definition named [typeName].
     *
     * If `d` is any type definition in this schema, `type(d.typeName) == d`. Throws when no type
     * with that name exists.
     *
     * @throws MissingSchemaElementException when [typeName] does not identify a schema type
     */
    fun type(typeName: String): Type

    /**
     * Returns the field at the exact schema coordinate [typeName]/[fieldName].
     *
     * This returns output fields only. It throws if the type is missing, the type is not composite,
     * or the named output field is missing. For every returned field `f`,
     * `field(f.containingType.typeName, f.fieldName) == f`.
     *
     * @throws MissingSchemaElementException when the coordinate does not identify an output field
     */
    fun field(
        typeName: String,
        fieldName: String,
    ): OutputField

    /**
     * Returns exactly the composite types that may be used as type conditions in the selection
     * set of [parentType], according to GraphQL fragment-spread validity.
     *
     * For composite types `a` and `b`, `b` is in `spreadableTypes(a)` exactly when `a == b` or their
     * [CompositeType.possibleTypes] sets have a common member. Thus the parent type itself is always
     * in the returned set, even when it has no possible object types, while distinct nominally
     * related interfaces with no common possible object are not spreadable. Spreadability is
     * symmetric.
     */
    fun spreadableTypes(parentType: CompositeType): Set<CompositeType>

    /**
     * Whether [fragmentType] may be used as a type condition in the selection set of [parentType],
     * according to GraphQL fragment-spread validity.
     *
     * This is true exactly when [fragmentType] is in `spreadableTypes(parentType)`.
     */
    fun isSpreadable(
        parentType: CompositeType,
        fragmentType: CompositeType,
    ): Boolean

    /**
     * The selection-set relation of the composite types [a] and [b].
     *
     * Nominal narrowing and fragment spreadability remain distinct. In particular, one interface
     * may be nominally narrower than another even when neither has a possible concrete object;
     * that fact alone does not make a fragment spread possible. A nominally narrower type's
     * possible-object set is a subset of the wider type's set, but set inclusion does not imply a
     * nominal relation. The result is a stipulated schema relation, not an algorithm derived solely
     * by comparing [CompositeType.possibleTypes].
     */
    fun relation(
        a: CompositeType,
        b: CompositeType,
    ): TypeRelation

    /**
     * The relation of the first composite type to the second.
     *
     * [SAME] holds exactly when both values denote the same canonical type. [WIDER_THAN] holds
     * exactly when the first type is an interface transitively implemented by the second object or
     * interface, or when the first is a union having the second object as a direct member.
     * [NARROWER_THAN] is exactly the converse. [COPARENT] holds exactly when neither type nominally
     * contains the other but some concrete object type is possible for both. [NONE] holds exactly
     * when none of the other relations does.
     *
     * Reversing the two types exchanges [WIDER_THAN] and [NARROWER_THAN] and preserves [SAME],
     * [COPARENT], and [NONE].
     */
    enum class TypeRelation {
        SAME,
        WIDER_THAN,
        NARROWER_THAN,
        COPARENT,
        NONE,
    }

    fun intValue(value: Int): IntValue = DefaultIntValue(value)

    fun floatValue(value: Double): FloatValue {
        require(value.isFinite()) { "GraphQL Float values must be finite" }
        return DefaultFloatValue(value)
    }

    fun stringValue(value: String): StringValue = DefaultStringValue(value)

    fun booleanValue(value: Boolean): BooleanValue = DefaultBooleanValue(value)

    fun idValue(value: String): IDValue = DefaultIDValue(value)

    fun enumValue(
        type: EnumType,
        value: String,
    ): EnumValue {
        require(value in type.values) {
            "$value is not a value of ${type.typeName}"
        }
        return DefaultEnumValue(
            type = type,
            enumValue = value,
        )
    }

    fun inputListValue(values: List<InputValue?>): InputListValue =
        DefaultInputListValue(values.toList())

    fun outputListValue(values: List<OutputValue?>): ListValue =
        DefaultListValue(values.toList())

    /** Constructs a list engine result whose elements are typed by their surrounding schema context. */
    fun listEngineResult(values: List<EngineResult?>): ListEngineResult =
        ListEngineResult.create(values)

    /**
     * Constructs a possibly partial object value whose keys belong to [type].
     *
     * A resolver may omit fields that it did not resolve. Distinct argument tuples for the same
     * field are distinct keys, and a present key may map to null.
     *
     * @throws IllegalArgumentException when a supplied key's field does not belong to [type] or its
     * arguments recursively contain an unresolved [VariableValue]
     */
    fun objectValue(
        type: ObjectType,
        fields: Map<ObjectKey, OutputValue?>,
    ): ObjectValue =
        DefaultObjectValue(
            type = type,
            fieldValues = ObjectFieldValues(type, fields.toMap()),
        )

    /**
     * Constructs an input-object value by converting each supplied host value according to its
     * schema field's [InputField.type].
     *
     * @throws ClassCastException when a field is unknown or a supplied value does not have the
     * required shape
     */
    fun inputObjectValue(
        type: InputObjectType,
        fields: Map<String, Any?>,
    ): InputObjectValue {
        val convertedFields =
            convertInputLikeFields(
                type = type,
                fields = fields,
                context = type.typeName,
            )
        return DefaultInputObjectValue(
            type = type,
            fieldValues = FieldValues(type, convertedFields),
        )
    }

    /**
     * Constructs an argument value by converting each supplied host value according to its
     * argument's [FieldArgument.type].
     *
     * @throws ClassCastException when an argument is unknown or a supplied value does not have the
     * required shape
     */
    fun argumentsValue(
        field: OutputField,
        fields: Map<String, Any?>,
    ): ArgumentsValue {
        val convertedFields =
            convertInputLikeFields(
                type = field.arguments,
                fields = fields,
                context = "${field.containingType.typeName}/${field.fieldName}",
            )
        return DefaultArgumentsValue(
            type = field.arguments,
            fieldValues = FieldValues(field.arguments, convertedFields),
        )
    }

    /**
     * Constructs a key for [field], which is canonical under the one-schema invariant, converting
     * each supplied host argument according to the field's argument definition.
     *
     * The returned key carries [field] itself, so its complete schema coordinate is closed over the
     * canonical definitions of this schema. Its argument value is typed by [OutputField.arguments].
     * This factory also constructs keys for abstract-type fields or unresolved arguments used
     * outside an [ObjectValue] or [ObjectEngineResult]. The concrete-field and instantiated-argument
     * constraints on keys present in those values are enforced by their respective carrier domains
     * rather than by this factory.
     *
     * @throws ClassCastException when an argument is unknown or a supplied value does not have the
     * required shape
     */
    fun objectKey(
        field: OutputField,
        arguments: Map<String, Any?>,
    ): ObjectKey =
        ObjectKey(
            field = field,
            arguments = argumentsValue(field, arguments),
        )

    fun variableValue(variableName: String): VariableValue =
        DefaultVariableValue(variableName)

    private fun convertInputLikeFields(
        type: InputObjectLike,
        fields: Map<String, Any?>,
        context: String,
    ): Map<String, InputValue?> =
        fields.mapValues { (fieldName, value) ->
            val field =
                type.fields[fieldName]
                    ?: throw ClassCastException(
                        "$context has no input field named $fieldName",
                    )
            convertInputValue(
                type = field.type,
                value = value,
                path = "$context.$fieldName",
            )
        }

    private fun convertInputValue(
        type: TypeExpr<InputType>,
        value: Any?,
        path: String,
    ): InputValue? {
        if (value == null) {
            if (!type.isNullable) {
                throw inputValueClassCast(path, type, value)
            }
            return null
        }
        if (value == ErrorValue) return ErrorValue
        if (value is VariableValue) return value

        return when (type) {
            is TypeExpr.Named ->
                convertNamedInputValue(
                    type = type.baseType,
                    value = value,
                    path = path,
                )

            is TypeExpr.List -> {
                val elements =
                    when (value) {
                        is InputListValue -> value.inputListValues
                        is List<*> -> value
                        else -> throw inputValueClassCast(path, type, value)
                    }
                inputListValue(
                    elements.mapIndexed { index, element ->
                        convertInputValue(
                            type = type.elementType,
                            value = element,
                            path = "$path[$index]",
                        )
                    },
                )
            }
        }
    }

    private fun convertNamedInputValue(
        type: InputType,
        value: Any,
        path: String,
    ): InputValue =
        when (type) {
            IntType ->
                when (value) {
                    is IntValue -> value
                    is Int -> intValue(value)
                    else -> throw inputValueClassCast(path, type, value)
                }

            FloatType ->
                when (value) {
                    is FloatValue -> value
                    is Double -> floatValue(value)
                    else -> throw inputValueClassCast(path, type, value)
                }

            StringType ->
                when (value) {
                    is StringValue -> value
                    is String -> stringValue(value)
                    else -> throw inputValueClassCast(path, type, value)
                }

            BooleanType ->
                when (value) {
                    is BooleanValue -> value
                    is Boolean -> booleanValue(value)
                    else -> throw inputValueClassCast(path, type, value)
                }

            IDType ->
                when (value) {
                    is IDValue -> value
                    is String -> idValue(value)
                    else -> throw inputValueClassCast(path, type, value)
                }

            is EnumType ->
                when (value) {
                    is EnumValue -> {
                        if (value.type != type) {
                            throw inputValueClassCast(path, type, value)
                        }
                        value
                    }

                    is String -> enumValue(type, value)
                    else -> throw inputValueClassCast(path, type, value)
                }

            is InputObjectType ->
                when (value) {
                    is InputObjectValue -> {
                        if (value.type != type) {
                            throw inputValueClassCast(path, type, value)
                        }
                        value
                    }

                    is Map<*, *> ->
                        inputObjectValue(
                            type = type,
                            fields = value.toStringKeyedMap(path),
                        )

                    else -> throw inputValueClassCast(path, type, value)
                }
        }

    private fun Map<*, *>.toStringKeyedMap(path: String): Map<String, Any?> =
        entries.associate { (key, value) ->
            if (key !is String) {
                throw ClassCastException(
                    "$path requires String field names, got ${key?.let { it::class.simpleName }}",
                )
            }
            key to value
        }

    private fun inputValueClassCast(
        path: String,
        type: TypeExpr<InputType>,
        value: Any?,
    ): ClassCastException =
        inputValueClassCast(path, type.baseType, value)

    private fun inputValueClassCast(
        path: String,
        type: InputType,
        value: Any?,
    ): ClassCastException =
        ClassCastException(
            "$path requires ${type.typeName}, got ${value?.let { it::class.simpleName } ?: "null"}",
        )

    /**
     * A GraphQL semantic value.
     *
     * Implementations are mathematical values: equality is value equality over the properties
     * exposed by the interface. When an input value contains unbound [VariableValue] instances,
     * equality is conservative as described by [VariableValue].
     *
     * ### Invariant: schema-value-canonicality
     *
     * Every schema definition carried by a value is the canonical definition from the
     * [Assumptions.schema] under which that value is interpreted.
     */
    sealed interface Value

    sealed interface InputValue : Value

    sealed interface OutputValue : Value

    sealed interface TypedValue : Value {
        val type: Type
    }

    sealed interface SimpleValue : InputValue, OutputValue, EngineResult {
        val type: SimpleType
    }

    /**
     * A value in the model's closed universe of built-in scalar leaf types: [IntValue],
     * [FloatValue], [StringValue], [BooleanValue], or [IDValue].
     *
     * Custom scalar values are not represented in this model. Therefore, this model can reason only
     * about schemas that do not have custom scalars.
     */
    sealed interface ScalarValue : SimpleValue {
        override val type: ScalarType
    }

    sealed interface IntValue : ScalarValue {
        override val type: IntType
            get() = IntType

        val intValue: Int
    }

    sealed interface FloatValue : ScalarValue {
        override val type: FloatType
            get() = FloatType

        /**
         * ### Invariant: schema-finite-float
         *
         * This value is finite; NaN and positive or negative infinity are excluded.
         */
        val floatValue: Double
    }

    sealed interface StringValue : ScalarValue {
        override val type: StringType
            get() = StringType

        val stringValue: String
    }

    sealed interface BooleanValue : ScalarValue {
        override val type: BooleanType
            get() = BooleanType

        val booleanValue: Boolean
    }

    sealed interface IDValue : ScalarValue {
        override val type: IDType
            get() = IDType

        val idValue: String
    }

    sealed interface EnumValue : SimpleValue {
        /**
         * ### Invariant: schema-enum-membership
         *
         * [type] is the canonical enum definition through which this value was constructed, and
         * [enumValue] is a member of [type].[EnumType.values].
         */
        override val type: EnumType
        val enumValue: String
    }

    /**
     * When an input list contains potentially nested unbound [VariableValue] instances, equality is
     * conservative as described by [VariableValue].
     */
    sealed interface InputListValue : InputValue {
        val inputListValues: List<InputValue?>
    }

    sealed interface ListValue : OutputValue {
        val outputListValues: List<OutputValue?>
    }

    /**
     * A value shaped like a GraphQL input object.
     *
     * ### Invariant: input-like-value-coherence
     *
     * [type] is the canonical input-object-like definition whose fields [fieldValues] inhabit.
     * `fieldValues.containingType == type`.
     *
     * ### Representation
     *
     * Implementations narrow [type] to match their corresponding definition category. This common
     * interface is not itself a GraphQL [Value], because an [ArgumentsValue] is a schema-synthetic
     * tuple rather than a GraphQL input value.
     */
    sealed interface InputLikeValue {
        val type: InputObjectLike
        val fieldValues: FieldValues<InputObjectLike, InputValue>
    }

    /**
     * An input object whose fields may contain nested unbound [VariableValue] instances.
     *
     * Equality is conservative when such variables are present as described by [VariableValue].
     */
    sealed interface InputObjectValue : InputValue, TypedValue, InputLikeValue {
        override val type: InputObjectType
        override val fieldValues: FieldValues<InputObjectType, InputValue>
    }

    /**
     * The values supplied for one output field's complete argument definition.
     *
     * A value may contain nested unbound [VariableValue] instances when it belongs to an
     * [ObjectKey] used outside an [ObjectValue] or [ObjectEngineResult]. Equality is structural
     * over [type] and [fieldValues]; no distinguished empty value is needed.
     */
    sealed interface ArgumentsValue : InputLikeValue {
        override val type: FieldArguments
        override val fieldValues: FieldValues<FieldArguments, InputValue>
    }

    /**
     * One alias-free output-field coordinate consisting of a canonical field and its arguments.
     *
     * ### Invariant: object-key-argument-definition
     *
     * `arguments.type == field.arguments`.
     *
     * ### Usage
     *
     * A key used in a selection may carry an abstract-type field or unresolved variables. A key in
     * an [ObjectValue] or [ObjectEngineResult] carries a field owned by that concrete object type
     * and contains no unresolved variables. Aliases do not participate in identity.
     *
     * Construct keys through [Schema.objectKey]. Equality is structural over [field] and
     * [arguments], using the canonical schema equality documented by [Schema].
     */
    @ConsistentCopyVisibility
    data class ObjectKey internal constructor(
        val field: OutputField,
        val arguments: ArgumentsValue,
    ) {
        init {
            require(arguments.type == field.arguments) {
                "Key arguments do not belong to its output field"
            }
        }
    }

    /**
     * A possibly partial object output.
     *
     * ### Invariant: object-value-owner
     *
     * `fieldValues.containingType == type`. Every present [ObjectKey] carries a field whose
     * containing type equals [type] and arguments containing no unresolved [VariableValue].
     */
    sealed interface ObjectValue : OutputValue, TypedValue {
        override val type: ObjectType
        val fieldValues: ObjectFieldValues
    }

    /**
     * A finite map from exact output-field coordinates to values.
     *
     * [containingType] is the concrete object type whose keys this map contains. Unlike
     * [FieldValues], this map is keyed by [ObjectKey] rather than field name, so it can represent
     * multiple argument tuples for one output field.
     *
     * Construct instances through [Schema.objectValue].
     */
    class ObjectFieldValues internal constructor(
        val containingType: ObjectType,
        private val backingMap: Map<ObjectKey, OutputValue?>,
    ) : Map<ObjectKey, OutputValue?> by backingMap {
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

        /** @throws MissingFieldException when [key] is not present */
        override operator fun get(key: ObjectKey): OutputValue? = getValue(key)

        /** @throws MissingFieldException when [key] is not present */
        fun getValue(key: ObjectKey): OutputValue? {
            if (!backingMap.containsKey(key)) {
                throw MissingFieldException(
                    containingType.typeName,
                    key.field.fieldName,
                )
            }
            return backingMap[key]
        }

        override fun equals(other: Any?): Boolean =
            other is ObjectFieldValues &&
                containingType == other.containingType &&
                backingMap == other.backingMap

        override fun hashCode(): Int =
            31 * containingType.hashCode() + backingMap.hashCode()

        override fun toString(): String = backingMap.toString()
    }

    /**
     * A map from field names to values.
     *
     * ### Invariant: field-values-owner
     *
     * [containingType] is the canonical [Type] or [FieldArguments] definition whose fields these
     * values inhabit, and every present key names one of its fields.
     *
     * ### Lookup
     *
     * Unlike an ordinary [Map], [get] and [getValue] throw [MissingFieldException] when the
     * requested field does not exist or has not been set. A present field may still map to null.
     *
     * [Map] extension functions such as [Map.getOrElse] may call [get] and therefore throw instead of
     * applying their fallback. Check [containsKey] before looking up a field whose presence is
     * unknown.
     */
    class FieldValues<out T : Any, out V : Value>(
        val containingType: T,
        private val backingMap: Map<String, V?>,
    ) : Map<String, V?> by backingMap {
        /** @throws MissingFieldException when [key] is not present */
        override operator fun get(key: String): V? = getValue(key)

        /** @throws MissingFieldException when [key] is not present */
        fun getValue(key: String): V? {
            if (!backingMap.containsKey(key)) {
                val typeName =
                    when (containingType) {
                        is Type -> containingType.typeName
                        is FieldArguments -> "\$ARGUMENTS"
                        else -> error("Unexpected field-value definition: $containingType")
                    }
                throw MissingFieldException(typeName, key)
            }
            return backingMap[key]
        }

        override fun equals(other: Any?): Boolean =
            other is FieldValues<*, *> &&
                containingType == other.containingType &&
                backingMap == other.backingMap

        override fun hashCode(): Int =
            31 * containingType.hashCode() + backingMap.hashCode()

        override fun toString(): String = backingMap.toString()
    }

    /**
     * A symbolic reference to an execution variable.
     *
     * We assume that variables involved in any execution all have unique, non-conflicting names, so
     * when two variable values with the same name appear, it is safe to assume that their actual
     * values will be the same. When comparing a variable value to another value, we attempt to look
     * up its value in [Assumptions.variableValues]. If an entry exists, the variable is bound
     * and we compare its bound value with the other value. [equals] returns false for an unbound
     * variable unless it is compared with an unbound variable of the same name.
     *
     * When [equals] involving variable values returns true, the two values are guaranteed to be equal
     * under all possible variable bindings. When [equals] involving only bound variable values
     * returns false, the two values are guaranteed not to be equal. However, when [equals] involving
     * an unbound variable returns false, the two values are not necessarily unequal and may become
     * equal depending on the eventual bindings.
     */
    sealed interface VariableValue : InputValue {
        val variableName: String
    }

    /**
     * The bottom value of the GraphQL value hierarchy.
     *
     * Kotlin has no user-definable bottom value, so this object explicitly implements every leaf
     * value interface. Its properties have no value and therefore cannot be observed.
     *
     * This model intentionally collapses all GraphQL error metadata, including messages, paths,
     * extensions, and error multiplicity, into this single value.
     */
    object ErrorValue :
        IntValue,
        FloatValue,
        StringValue,
        BooleanValue,
        IDValue,
        EnumValue,
        InputListValue,
        ListValue,
        InputObjectValue,
        ObjectValue,
        VariableValue {
        override val type: Nothing
            get() = unsupported()

        override val intValue: Int
            get() = unsupported()

        override val floatValue: Double
            get() = unsupported()

        override val stringValue: String
            get() = unsupported()

        override val booleanValue: Boolean
            get() = unsupported()

        override val idValue: String
            get() = unsupported()

        override val enumValue: String
            get() = unsupported()

        override val inputListValues: List<InputValue?>
            get() = unsupported()

        override val outputListValues: List<OutputValue?>
            get() = unsupported()

        override val fieldValues: Nothing
            get() = unsupported()

        override val variableName: String
            get() = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("Schema.ErrorValue has no observable properties")
    }

    /**
     * A named type definition.
     *
     * ### Invariant: schema-type-name-uniqueness
     *
     * Definitions use the canonical equality documented by [Schema]: `a == b` exactly when `a` and
     * `b` represent the same schema type. Equivalently, two type definitions have the same
     * [typeName] exactly when they are equal. The permitted concrete categories are exhaustively
     * scalar, enum, object, interface, union, or input object.
     */
    sealed interface Type {
        val typeName: String
    }

    /**
     * A type permitted as the base type of a GraphQL input value.
     *
     * The input types are exactly scalars, enums, and input objects.
     */
    sealed interface InputType : Type

    /**
     * A type permitted as the base type of a GraphQL output value.
     *
     * The output types are exactly scalars, enums, objects, interfaces, and unions.
     */
    sealed interface OutputType : Type

    /** Exactly the scalar and enum types, which are both input and output types. */
    sealed interface SimpleType : InputType, OutputType

    /**
     * A type on which GraphQL selection sets and type conditions are meaningful.
     *
     * ### Invariant: schema-composite-field-graph
     *
     * [fields] contains all fields selectable at this type, rather than only fields declared in
     * SDL. Every composite type `t` has exactly one owner-specific, schema-synthetic GraphQL
     * meta-field `f` for which:
     *
     * - `f.fieldName == "__typename"`;
     * - `f.containingType == t`;
     * - `f.type == TypeExpr.Named(StringType, isNullable = false)`;
     * - `f.arguments == NoArguments`; and
     * - `field(t.typeName, "__typename") == f`.
     *
     * Each map key equals its field's [OutputField.fieldName], and each field's
     * [OutputField.containingType] is this definition. Conversely, every [OutputField] in the
     * schema occurs exactly once in its containing definition's map. Flattened copies at different
     * schema coordinates are distinct canonical definitions even when their signatures match.
     * Effective object and interface fields satisfy GraphQL interface-field compatibility for every
     * interface they implement.
     */
    sealed interface CompositeType : OutputType {
        val fields: Map<String, OutputField>

        /**
         * Exactly the concrete object types that may occur at runtime for this type.
         *
         * ### Invariant: schema-composite-possible-types
         *
         * An object type contains only itself. An interface contains all of its direct and indirect
         * implementing object types. A union contains its member object types. The set is therefore
         * non-empty for an object but may be empty for an interface or union. Every member is a
         * canonical definition in the containing schema.
         */
        val possibleTypes: Set<ObjectType>
    }

    /**
     * A scalar in the model's fixed universe of built-in GraphQL scalar types.
     *
     * ### Invariant: schema-scalar-universe
     *
     * The instances are exactly [IntType], [FloatType], [StringType], [BooleanType], and [IDType],
     * and their [Type.typeName] values are fixed by those declarations. Any scalar reachable from
     * this schema is the corresponding singleton.
     */
    sealed class ScalarType protected constructor(
        final override val typeName: String,
    ) : SimpleType

    object IntType : ScalarType("Int")

    object FloatType : ScalarType("Float")

    object StringType : ScalarType("String")

    object BooleanType : ScalarType("Boolean")

    object IDType : ScalarType("ID")

    /**
     * An enum whose [values] are exactly its finite set of legal GraphQL enum value names.
     *
     * ### Invariant: schema-enum-values
     *
     * The set has no modeled order, and each value is represented only by its name.
     */
    class EnumType(
        override val typeName: String,
        val values: Set<String>,
    ) : SimpleType

    /**
     * An object type.
     *
     * [fields] contains `__typename` and the object's effective fields after flattening type
     * extensions and inherited interface fields. Interface implementation relationships are
     * represented by the schema's relation operations rather than stored on this definition.
     */
    class ObjectType(
        override val typeName: String,
        override val fields: Map<String, OutputField>,
        override val possibleTypes: Set<ObjectType>,
    ) : CompositeType

    /**
     * An interface type.
     *
     * [fields] contains `__typename` and the interface's effective fields after flattening type
     * extensions and inherited interface fields. Parent-interface and implementation
     * relationships are represented by the schema's relation operations rather than stored here.
     */
    class InterfaceType(
        override val typeName: String,
        override val fields: Map<String, OutputField>,
        override val possibleTypes: Set<ObjectType>,
    ) : CompositeType

    /**
     * A union definition.
     *
     * ### Invariant: schema-union-fields
     *
     * Union membership is represented by [CompositeType.possibleTypes]. [fields] contains exactly
     * the `__typename` field.
     */
    class UnionType(
        override val typeName: String,
        override val fields: Map<String, OutputField>,
        override val possibleTypes: Set<ObjectType>,
    ) : CompositeType

    /**
     * A schema definition shaped like a GraphQL input object.
     *
     * ### Invariant: schema-input-field-graph
     *
     * The instances are named [InputObjectType] definitions and schema-synthetic [FieldArguments]
     * definitions. Each [fields] key equals its field's [InputLikeField.name], and each field's
     * [InputLikeField.containingType] is this definition.
     */
    sealed interface InputObjectLike {
        val fields: Map<String, InputLikeField>
    }

    /**
     * An input object definition.
     *
     * Every [InputField] occurs exactly once in its containing definition's map.
     */
    class InputObjectType(
        override val typeName: String,
        override val fields: Map<String, InputField>,
    ) : InputType, InputObjectLike

    /**
     * The complete argument definition of an output field.
     *
     * ### Invariant: schema-field-argument-graph
     *
     * This is schema-synthetic rather than a named [Type], and it cannot occur in a [TypeExpr].
     * Each non-empty instance belongs to exactly one [OutputField]. The empty argument definition
     * is always represented by the singleton [NoArguments] and is shared by every field that takes
     * no arguments.
     */
    class FieldArguments private constructor(
        override val fields: Map<String, FieldArgument>,
    ) : InputObjectLike {
        internal companion object {
            private val EMPTY = FieldArguments(emptyMap())

            internal fun empty(): FieldArguments = EMPTY

            internal fun <T> of(
                definitions: Collection<T>,
                name: (T) -> String,
                createField: (T, FieldArguments) -> FieldArgument,
            ): FieldArguments {
                if (definitions.isEmpty()) return EMPTY

                val fields = linkedMapOf<String, FieldArgument>()
                val result = FieldArguments(fields)
                definitions.forEach { definition ->
                    val argumentName = name(definition)
                    require(argumentName !in fields) {
                        "Duplicate field argument: $argumentName"
                    }
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
        }
    }

    /**
     * The canonical output field at [containingType]/[fieldName].
     *
     * ### Invariant: schema-output-field-coordinate
     *
     * `containingType.fields[fieldName] == this`, and [type]'s base type is canonical in the same
     * schema. [arguments] is the input-object-like definition of the complete argument tuple.
     */
    class OutputField(
        val fieldName: String,
        val containingType: CompositeType,
        val type: TypeExpr<OutputType>,
        val arguments: FieldArguments,
    )

    /**
     * A field of an input-object-like definition.
     *
     * ### Invariant: schema-input-like-field-coordinate
     *
     * `containingType.fields[name] == this`, and [type]'s base type is canonical in the same
     * schema. [defaultValue], when present, is valid for [type].
     */
    sealed interface InputLikeField {
        val name: String
        val containingType: InputObjectLike
        val type: TypeExpr<InputType>
        val defaultValue: DefaultValue

        val isRequired: Boolean
            get() = !type.isNullable && defaultValue == DefaultValue.Absent
    }

    /** The canonical input-object field at [containingType]/[fieldName]. */
    class InputField(
        val fieldName: String,
        override val containingType: InputObjectType,
        override val type: TypeExpr<InputType>,
        override val defaultValue: DefaultValue,
    ) : InputLikeField {
        override val name: String
            get() = fieldName
    }

    /**
     * The canonical argument named [argumentName] in [containingType].
     *
     * `containingType.fields[argumentName] == this`.
     */
    class FieldArgument(
        val argumentName: String,
        override val containingType: FieldArguments,
        override val type: TypeExpr<InputType>,
        override val defaultValue: DefaultValue,
    ) : InputLikeField {
        override val name: String
            get() = argumentName
    }

    /**
     * A finite, well-founded GraphQL value-type expression.
     *
     * ### Invariant: schema-type-expression-well-foundedness
     *
     * Nullability belongs independently to every named or list layer. [isNullable] describes the
     * outermost layer of this expression. [baseType] is the named type beneath every list wrapper,
     * and [isBaseTypeNullable] is that named layer's nullability. Wherever a type expression is
     * embedded in a schema definition, [baseType] is that schema's canonical type definition.
     *
     * ### Equality
     *
     * Type expressions use structural equality over their complete wrapper shape, nullability, and
     * canonical base type.
     */
    sealed interface TypeExpr<out T : Type> {
        val baseType: T
        val isNullable: Boolean
        val isBaseTypeNullable: Boolean

        data class Named<out T : Type>(
            override val baseType: T,
            override val isNullable: Boolean = true,
        ) : TypeExpr<T> {
            override val isBaseTypeNullable: Boolean
                get() = isNullable
        }

        data class List<out T : Type>(
            val elementType: TypeExpr<T>,
            override val isNullable: Boolean = true,
        ) : TypeExpr<T> {
            override val baseType: T
                get() = elementType.baseType

            override val isBaseTypeNullable: Boolean
                get() = elementType.isBaseTypeNullable
        }
    }

    /**
     * An optional, fully coerced semantic default.
     *
     * ### Invariant: schema-default-value-conformance
     *
     * [Absent] means that no default is declared. [Present.value] may be null, denoting an explicit
     * GraphQL null; absence and explicit null are distinct. When attached to a field or argument,
     * [Present] is valid for its declaring [TypeExpr]: null is permitted only when the expression's
     * outer layer is nullable, and a non-null value conforms recursively to every list and named
     * layer, including enum membership and input-object fields. It does not recursively contain
     * [VariableValue] or [ErrorValue]. Defaults are semantic values after input
     * coercion, not source literals.
     *
     * ### Equality
     *
     * Default values use structural equality.
     */
    sealed interface DefaultValue {
        data object Absent : DefaultValue

        data class Present(
            val value: InputValue?,
        ) : DefaultValue
    }

    /**
     * Indicates that a partial schema lookup has no result at the requested coordinate.
     *
     * [fieldName] is null for a type coordinate and non-null for an output-field coordinate. This
     * exception is part of the mathematical lookup contract; it does not model a recoverable
     * runtime failure.
     */
    class MissingSchemaElementException(
        val typeName: String,
        val fieldName: String? = null,
    ) : NoSuchElementException(
            if (fieldName == null) {
                "Missing schema type: $typeName"
            } else {
                "Missing schema field: $typeName/$fieldName"
            },
        )
}

class MissingFieldException(
    val typeName: String,
    val fieldName: String,
) : NoSuchElementException("Missing field: $typeName.$fieldName")

private data class DefaultIntValue(
    override val intValue: Int,
) : Schema.IntValue

private data class DefaultFloatValue(
    override val floatValue: Double,
) : Schema.FloatValue

private data class DefaultStringValue(
    override val stringValue: String,
) : Schema.StringValue

private data class DefaultBooleanValue(
    override val booleanValue: Boolean,
) : Schema.BooleanValue

private data class DefaultIDValue(
    override val idValue: String,
) : Schema.IDValue

private data class DefaultEnumValue(
    override val type: Schema.EnumType,
    override val enumValue: String,
) : Schema.EnumValue

private data class DefaultInputListValue(
    override val inputListValues: List<Schema.InputValue?>,
) : Schema.InputListValue

private data class DefaultListValue(
    override val outputListValues: List<Schema.OutputValue?>,
) : Schema.ListValue

private data class DefaultObjectValue(
    override val type: Schema.ObjectType,
    override val fieldValues: Schema.ObjectFieldValues,
) : Schema.ObjectValue

private data class DefaultInputObjectValue(
    override val type: Schema.InputObjectType,
    override val fieldValues:
        Schema.FieldValues<Schema.InputObjectType, Schema.InputValue>,
) : Schema.InputObjectValue

private data class DefaultArgumentsValue(
    override val type: Schema.FieldArguments,
    override val fieldValues:
        Schema.FieldValues<Schema.FieldArguments, Schema.InputValue>,
) : Schema.ArgumentsValue

private data class DefaultVariableValue(
    override val variableName: String,
) : Schema.VariableValue

private fun Schema.InputValue?.containsVariableValue(): Boolean =
    when {
        this == null || this == Schema.ErrorValue -> false
        this is Schema.VariableValue -> true
        this is Schema.InputListValue ->
            inputListValues.any { it.containsVariableValue() }

        this is Schema.InputObjectValue ->
            fieldValues.values.any { it.containsVariableValue() }

        else -> false
    }
