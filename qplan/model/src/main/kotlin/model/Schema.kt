package model

/**
 * A finite GraphQL schema view used as an input to the correctness model.
 *
 * Exactly one instance is supplied by [Assumptions.schema] for a reasoning world. Concrete
 * implementations use `@Singleton` to record this modeling assumption for dependency injection.
 *
 * Definitions are canonical within this schema: each type name, field coordinate, input-field
 * coordinate, and argument coordinate identifies exactly one definition object. For every
 * definition `d` reachable from this schema, `type(d.typeName) === d` when `d` is a [Type];
 * the corresponding owner map contains `d` by its declared name for nested definitions. Every
 * [TypeExpr.baseType] reachable from the schema is likewise the canonical result of [type].
 *
 * Construct [Value] instances through this schema's value factory methods. Those methods require
 * every carried definition to be canonical in this schema, so reasoning within the one-schema
 * world may assume same-schema ownership without repeating validation. Other modeling domains may
 * instead use names and coordinates. Nested definitions navigate to their canonical owners through
 * [OutputField.containingType] and [InputLikeField.containingType]. Definition objects use identity
 * equality; only acyclic value objects such as [TypeExpr] and [DefaultValue] use structural
 * equality.
 *
 * [query] is the canonical `Query` [ObjectType] and is always the query root. The only permitted
 * scalar definitions are the five [ScalarType] singletons; whenever one belongs to this schema,
 * [type] returns that singleton.
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
         * For every [OutputField] `f`, `f.arguments == NoArguments` exactly when `f` takes no
         * arguments.
         */
        @JvmField
        val NoArguments: FieldArguments = FieldArguments.empty()
    }

    /**
     * The canonical query root.
     *
     * Invariants: `query.typeName == "Query"` and `type("Query") === query`.
     */
    val query: ObjectType

    /**
     * Returns the canonical definition named [typeName].
     *
     * If `d` is any type definition in this schema, `type(d.typeName) === d`. Throws when no type
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
     * `field(f.containingType.typeName, f.fieldName) === f`.
     *
     * @throws MissingSchemaElementException when the coordinate does not identify an output field
     */
    fun field(
        typeName: String,
        fieldName: String,
    ): OutputField

    /**
     * Returns exactly the concrete object types that may occur at runtime for [typeName].
     *
     * An object type maps to the singleton set containing its own name. An interface maps to all
     * of its direct and indirect implementing object types. A union maps to its member object
     * types. An object result is therefore never empty; an interface or union result may be empty.
     * An empty set means that [typeName] is composite but has no possible object types. Null means
     * that the named type exists but is not composite. Every name in a non-null result resolves
     * through [type] to an [ObjectType].
     *
     * @throws MissingSchemaElementException when [typeName] does not identify a schema type
     */
    fun possibleObjectTypes(typeName: String): Set<String>?

    /**
     * Returns exactly the composite types that may be used as type conditions in the selection
     * set of [parentTypeName], according to GraphQL fragment-spread validity.
     *
     * For composite types `a` and `b`, `b` is in `spreadableTypes(a)` exactly when `a == b` or their
     * [possibleObjectTypes] sets have a common member. Thus the parent type itself is always in the
     * returned set, even when it has no possible object types, while distinct nominally related
     * interfaces with no common possible object are not spreadable. Every returned name resolves
     * through [type] to a [CompositeType]. Spreadability is symmetric. Null means that the named
     * type exists but is not composite.
     *
     * @throws MissingSchemaElementException when [parentTypeName] does not identify a schema type
     */
    fun spreadableTypes(parentTypeName: String): Set<String>?

    /**
     * Whether [fragmentTypeName] may be used as a type condition in the selection set of
     * [parentTypeName], according to GraphQL fragment-spread validity.
     *
     * For two composite types, this is true exactly when [fragmentTypeName] is in
     * `spreadableTypes(parentTypeName)`.
     *
     * Null means that at least one named type exists but is not composite. If either name does not
     * exist, this throws [MissingSchemaElementException].
     *
     * @throws MissingSchemaElementException when either name does not identify a schema type
     */
    fun isSpreadable(
        parentTypeName: String,
        fragmentTypeName: String,
    ): Boolean?

    /**
     * The selection-set relation of the composite types [aTypeName] and [bTypeName].
     *
     * Null means that at least one named type exists but is not composite. If either name does not
     * exist, this throws [MissingSchemaElementException].
     *
     * Nominal narrowing and fragment spreadability remain distinct. In particular, one interface
     * may be nominally narrower than another even when neither has a possible concrete object;
     * that fact alone does not make a fragment spread possible. A nominally narrower type's
     * possible-object set is a subset of the wider type's set, but set inclusion does not imply a
     * nominal relation. The result is a stipulated schema relation, not an algorithm derived solely
     * by comparing [possibleObjectTypes] results.
     *
     * @throws MissingSchemaElementException when either name does not identify a schema type
     */
    fun relation(
        aTypeName: String,
        bTypeName: String,
    ): TypeRelation?

    /**
     * The relation of the first composite type to the second.
     *
     * [SAME] holds exactly when both names denote the same canonical type. [WIDER_THAN] holds
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

    fun intValue(value: kotlin.Int): IntValue {
        requireCanonicalType(ScalarType.Int)
        return DefaultIntValue(value)
    }

    fun floatValue(value: Double): FloatValue {
        requireCanonicalType(ScalarType.Float)
        require(value.isFinite()) { "GraphQL Float values must be finite" }
        return DefaultFloatValue(value)
    }

    fun stringValue(value: kotlin.String): StringValue {
        requireCanonicalType(ScalarType.String)
        return DefaultStringValue(value)
    }

    fun booleanValue(value: Boolean): BooleanValue {
        requireCanonicalType(ScalarType.Boolean)
        return DefaultBooleanValue(value)
    }

    fun idValue(value: kotlin.String): IDValue {
        requireCanonicalType(ScalarType.ID)
        return DefaultIDValue(value)
    }

    fun enumValue(
        type: EnumType,
        value: kotlin.String,
    ): EnumValue {
        requireCanonicalType(type)
        require(value in type.values) {
            "$value is not a value of ${type.typeName}"
        }
        return DefaultEnumValue(type, value)
    }

    fun inputListValue(values: List<InputValue?>): InputListValue {
        values.forEach(::requireCanonicalValue)
        return DefaultInputListValue(values.toList())
    }

    fun inputObjectValue(
        type: InputObjectType,
        fields: Map<kotlin.String, InputValue?>,
    ): InputObjectValue {
        requireCanonicalType(type)
        fields.values.forEach(::requireCanonicalValue)
        return DefaultInputObjectValue(
            type = type,
            inputObjectFields = FieldValues(type, fields.toMap()),
        )
    }

    fun variableValue(variableName: kotlin.String): VariableValue =
        DefaultVariableValue(variableName)

    private fun requireCanonicalType(type: Type) {
        require(this.type(type.typeName) === type) {
            "${type.typeName} is not canonical in this schema"
        }
    }

    private fun requireCanonicalValue(value: InputValue?) {
        if (value == null || value === ErrorValue) return

        when (value) {
            is ScalarValue -> requireCanonicalType(value.type)
            is EnumValue -> requireCanonicalType(value.type)
            is InputListValue -> value.inputListValues.forEach(::requireCanonicalValue)
            is InputObjectValue -> {
                requireCanonicalType(value.type)
                value.inputObjectFields.values.forEach(::requireCanonicalValue)
            }
            is VariableValue -> Unit
        }
    }

    /**
     * A GraphQL semantic value.
     *
     * Implementations are mathematical values: equality is value equality over the properties
     * exposed by the interface. When an input value contains unbound [VariableValue] instances,
     * equality is conservative as described by [VariableValue].
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

    sealed interface SimpleValue : InputValue, OutputValue, TypedValue, EngineResult {
        override val type: SimpleType

        override val typeName: kotlin.String
            get() = type.typeName
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
        override val type: ScalarType
            get() = ScalarType.Int

        val intValue: kotlin.Int
    }

    sealed interface FloatValue : ScalarValue {
        override val type: ScalarType
            get() = ScalarType.Float

        /** Invariant: this value is finite; NaN and positive or negative infinity are excluded. */
        val floatValue: Double
    }

    sealed interface StringValue : ScalarValue {
        override val type: ScalarType
            get() = ScalarType.String

        val stringValue: kotlin.String
    }

    sealed interface BooleanValue : ScalarValue {
        override val type: ScalarType
            get() = ScalarType.Boolean

        val booleanValue: Boolean
    }

    sealed interface IDValue : ScalarValue {
        override val type: ScalarType
            get() = ScalarType.ID

        val idValue: kotlin.String
    }

    sealed interface EnumValue : SimpleValue {
        override val type: EnumType

        val enumValue: kotlin.String
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
     * An input object whose fields may contain nested unbound [VariableValue] instances.
     *
     * Equality is conservative when such variables are present as described by [VariableValue].
     */
    sealed interface InputObjectValue : InputValue, TypedValue {
        override val type: InputObjectType
        val inputObjectFields: FieldValues<InputValue>
    }

    sealed interface ObjectValue : OutputValue, TypedValue {
        override val type: ObjectType
        val outputObjectFields: FieldValues<OutputValue>
    }

    /**
     * A map from field names to values.
     *
     * [containingType] is the canonical schema definition whose fields these values inhabit. Unlike
     * an ordinary [Map], [get] and [getValue] throw [MissingFieldException] when the requested field
     * does not exist or, for an output object, has not been set. A present field may still map to
     * null.
     *
     * [Map] extension functions such as [Map.getOrElse] may call [get] and therefore throw instead of
     * applying their fallback. Check [containsKey] before looking up a field whose presence is
     * unknown.
     */
    class FieldValues<out V : Value>(
        val containingType: Type,
        private val backingMap: Map<kotlin.String, V?>,
    ) : Map<kotlin.String, V?> by backingMap {
        /** @throws MissingFieldException when [key] is not present */
        override operator fun get(key: kotlin.String): V? = getValue(key)

        /** @throws MissingFieldException when [key] is not present */
        fun getValue(key: kotlin.String): V? {
            if (!backingMap.containsKey(key)) {
                throw MissingFieldException(containingType.typeName, key)
            }
            return backingMap[key]
        }

        override fun equals(other: Any?): Boolean =
            other is FieldValues<*> &&
                containingType === other.containingType &&
                backingMap == other.backingMap

        override fun hashCode(): kotlin.Int =
            31 * System.identityHashCode(containingType) + backingMap.hashCode()

        override fun toString(): kotlin.String = backingMap.toString()
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
        val variableName: kotlin.String
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

        override val typeName: kotlin.String
            get() = unsupported()

        override val intValue: kotlin.Int
            get() = unsupported()

        override val floatValue: Double
            get() = unsupported()

        override val stringValue: kotlin.String
            get() = unsupported()

        override val booleanValue: Boolean
            get() = unsupported()

        override val idValue: kotlin.String
            get() = unsupported()

        override val enumValue: kotlin.String
            get() = unsupported()

        override val inputListValues: List<InputValue?>
            get() = unsupported()

        override val outputListValues: List<OutputValue?>
            get() = unsupported()

        override val inputObjectFields: FieldValues<InputValue>
            get() = unsupported()

        override val outputObjectFields: FieldValues<OutputValue>
            get() = unsupported()

        override val variableName: kotlin.String
            get() = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("Schema.ErrorValue has no observable properties")
    }

    /**
     * A named type definition.
     *
     * Definitions have identity equality. Because definitions are canonical within [Schema],
     * definition identity coincides with schema-coordinate identity: two type definitions have the
     * same [typeName] exactly when they are the same object. The permitted concrete categories are
     * exhaustively scalar, enum, object, interface, union, or input object.
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
     * [fields] contains all fields selectable at this type, rather than only fields declared in
     * SDL. Every composite type `t` has exactly one owner-specific, schema-synthetic GraphQL
     * meta-field `f` for which:
     *
     * - `f.fieldName == "__typename"`;
     * - `f.containingType === t`;
     * - `f.type == TypeExpr.Named(ScalarType.String, isNullable = false)`;
     * - `f.arguments == NoArguments`; and
     * - `field(t.typeName, "__typename") === f`.
     *
     * Each map key equals its field's [OutputField.fieldName], and each field's
     * [OutputField.containingType] is this definition. Conversely, every [OutputField] in the
     * schema occurs exactly once in its containing definition's map. Flattened copies at different
     * schema coordinates are distinct canonical definitions even when their signatures match.
     */
    sealed interface CompositeType : OutputType {
        val fields: Map<String, OutputField>

        /**
         * Exactly the concrete object types that may occur at runtime for this type.
         *
         * An object type contains only itself. An interface contains all of its direct and indirect
         * implementing object types. A union contains its member object types. The set is therefore
         * non-empty for an object but may be empty for an interface or union. Every member is a
         * canonical definition in the containing schema, and its set of type names equals the
         * result of [Schema.possibleObjectTypes] for this type.
         */
        val possibleTypes: Set<ObjectType>
    }

    /**
     * A scalar in the model's fixed universe of built-in GraphQL scalar types.
     *
     * The instances are exactly [Int], [Float], [String], [Boolean], and [ID], and their
     * [Type.typeName] values are fixed by those declarations. Any scalar reachable from this
     * schema is the corresponding singleton.
     */
    sealed class ScalarType private constructor(
        final override val typeName: kotlin.String,
    ) : SimpleType {
        object Int : ScalarType("Int")

        object Float : ScalarType("Float")

        object String : ScalarType("String")

        object Boolean : ScalarType("Boolean")

        object ID : ScalarType("ID")
    }

    /**
     * An enum whose [values] are exactly its finite set of legal GraphQL enum value names.
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
     * represented by the schema's relation operations rather than stored on this definition. For
     * every interface this object implements, these effective fields satisfy GraphQL's
     * interface-field compatibility rules.
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
     * For every parent interface this interface implements, these effective fields satisfy
     * GraphQL's interface-field compatibility rules.
     */
    class InterfaceType(
        override val typeName: String,
        override val fields: Map<String, OutputField>,
        override val possibleTypes: Set<ObjectType>,
    ) : CompositeType

    /**
     * A union definition.
     *
     * Union membership is represented by [CompositeType.possibleTypes] and
     * [Schema.possibleObjectTypes]. [fields] contains exactly the `__typename` field.
     */
    class UnionType(
        override val typeName: String,
        override val fields: Map<String, OutputField>,
        override val possibleTypes: Set<ObjectType>,
    ) : CompositeType

    /**
     * A schema definition shaped like a GraphQL input object.
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
                    require(field.containingType === result) {
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
     * `containingType.fields[fieldName] === this`, and [type]'s base type is canonical in the same
     * schema. [arguments] is the input-object-like definition of the complete argument tuple.
     * It is [NoArguments] exactly when this field takes no arguments.
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
     * `containingType.fields[name] === this`, and [type]'s base type is canonical in the same
     * schema. [defaultValue], when present, is valid for [type].
     */
    sealed interface InputLikeField {
        val name: String
        val containingType: InputObjectLike
        val type: TypeExpr<InputType>
        val defaultValue: DefaultValue

        val isRequired: Boolean
            get() = !type.isNullable && defaultValue === DefaultValue.Absent
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
     * `containingType.fields[argumentName] === this`.
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
     * Nullability belongs independently to every named or list layer. [isNullable] describes the
     * outermost layer of this expression. [baseType] is the named type beneath every list wrapper,
     * and [isBaseTypeNullable] is that named layer's nullability. Wherever a type expression is
     * embedded in a schema definition, [baseType] is that schema's canonical type definition.
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
     * [Absent] means that no default is declared. [Present.value] may be null, denoting an explicit
     * GraphQL null; absence and explicit null are distinct. When attached to a field or argument,
     * [Present] is valid for its declaring [TypeExpr]: null is permitted only when the expression's
     * outer layer is nullable, and a non-null value conforms recursively to every list and named
     * layer, including enum membership and input-object fields. It does not recursively contain
     * [VariableValue] or [ErrorValue]. Defaults are semantic values after input
     * coercion, not source literals. Default values use structural equality.
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

private data class DefaultInputObjectValue(
    override val type: Schema.InputObjectType,
    override val inputObjectFields: Schema.FieldValues<Schema.InputValue>,
) : Schema.InputObjectValue

private data class DefaultVariableValue(
    override val variableName: String,
) : Schema.VariableValue
