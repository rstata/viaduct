package model

/**
 * The global schema against which operations, values, and results are interpreted.
 *
 * Whenever reasoning uses this model, its assumptions must state the expected contents of
 * [schema]. Those assumptions, rather than its nonexistent startup value, determine the canonical
 * type and field definitions and the type relations returned by its operations.
 *
 * As with [variableValues], every occurrence of this global refers to the same value throughout
 * one reasoning exercise.
 */
val schema: Schema = establishAssumptions()

/**
 * A finite GraphQL schema view used as an input to the correctness model.
 *
 * Definitions are canonical within this schema: each type name, field coordinate, input-field
 * coordinate, and argument coordinate identifies exactly one definition object. For every
 * definition `d` reachable from this schema, `type(d.typeName) === d` when `d` is a [Type];
 * the corresponding owner map contains `d` by its declared name for nested definitions. Every
 * [TypeExpr.baseType] reachable from the schema is likewise the canonical result of [type].
 *
 * References outside the schema use names. Nested definitions instead navigate to their canonical
 * owners through [OutputField.containingType], [InputField.containingType], and
 * [FieldArgument.containingField]. Definition objects use identity equality; only acyclic value
 * objects such as [TypeExpr] and [DefaultValue] use structural equality.
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
     */
    @Throws(MissingSchemaElementException::class)
    fun type(typeName: String): Type

    /**
     * Returns the field at the exact schema coordinate [typeName]/[fieldName].
     *
     * This returns output fields only. It throws if the type is missing, the type is not composite,
     * or the named output field is missing. For every returned field `f`,
     * `field(f.containingType.typeName, f.fieldName) === f`.
     */
    @Throws(MissingSchemaElementException::class)
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
     */
    @Throws(MissingSchemaElementException::class)
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
     */
    @Throws(MissingSchemaElementException::class)
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
     */
    @Throws(MissingSchemaElementException::class)
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
     */
    @Throws(MissingSchemaElementException::class)
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
     * - `f.arguments` is empty; and
     * - `field(t.typeName, "__typename") === f`.
     *
     * Each map key equals its field's [OutputField.fieldName], and each field's
     * [OutputField.containingType] is this definition. Conversely, every [OutputField] in the
     * schema occurs exactly once in its containing definition's map. Flattened copies at different
     * schema coordinates are distinct canonical definitions even when their signatures match.
     */
    sealed interface CompositeType : OutputType {
        val fields: Map<String, OutputField>
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
    ) : CompositeType

    /**
     * A union definition.
     *
     * Union membership is represented by [Schema.possibleObjectTypes], rather than by members
     * stored on this definition. [fields] contains exactly the `__typename` field.
     */
    class UnionType(
        override val typeName: String,
        override val fields: Map<String, OutputField>,
    ) : CompositeType

    /**
     * An input object definition.
     *
     * Each [fields] key equals its field's [InputField.fieldName], and each field's
     * [InputField.containingType] is this definition. Conversely, every [InputField] occurs
     * exactly once in its containing definition's map.
     */
    class InputObjectType(
        override val typeName: String,
        val fields: Map<String, InputField>,
    ) : InputType

    /**
     * The canonical output field at [containingType]/[fieldName].
     *
     * `containingType.fields[fieldName] === this`, and [type]'s base type is canonical in the same
     * schema. Each [arguments] key equals its argument's [FieldArgument.argumentName], and each
     * argument's [FieldArgument.containingField] is this field. Conversely, every [FieldArgument]
     * occurs exactly once in its containing field's map.
     */
    class OutputField(
        val fieldName: String,
        val containingType: CompositeType,
        val type: TypeExpr<OutputType>,
        val arguments: Map<String, FieldArgument>,
    )

    /**
     * The canonical input field at [containingType]/[fieldName].
     *
     * `containingType.fields[fieldName] === this`, and [type]'s base type is canonical in the same
     * schema. [defaultValue], when present, is valid for [type]. The field is required exactly when
     * `!type.isNullable && defaultValue === DefaultValue.Absent`.
     */
    class InputField(
        val fieldName: String,
        val containingType: InputObjectType,
        val type: TypeExpr<InputType>,
        val defaultValue: DefaultValue,
    )

    /**
     * The canonical argument named [argumentName] on [containingField].
     *
     * `containingField.arguments[argumentName] === this`, and [type]'s base type is canonical in the
     * same schema. [defaultValue], when present, is valid for [type]. The argument is required
     * exactly when `!type.isNullable && defaultValue === DefaultValue.Absent`.
     */
    class FieldArgument(
        val argumentName: String,
        val containingField: OutputField,
        val type: TypeExpr<InputType>,
        val defaultValue: DefaultValue,
    )

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
     * [GraphQLVariableValue] or [GraphQLErrorValue]. Defaults are semantic values after input
     * coercion, not source literals. Default values use structural equality.
     */
    sealed interface DefaultValue {
        data object Absent : DefaultValue

        data class Present(
            val value: GraphQLInputValue?,
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
