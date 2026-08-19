package model

/**
 * A finite GraphQL schema view used as an input to the correctness model.
 *
 * Exactly one instance is supplied by [Assumptions.schema] for a reasoning world.
 * Dependency-injection composition scopes that schema binding as a singleton.
 *
 * ### Invariant: schema-canonical-definition-graph
 *
 * Definitions are canonical within this schema: each type name, enum-value coordinate, field
 * coordinate, input-field coordinate, and argument coordinate identifies exactly one definition object. Definition
 * classes do not override `Any.equals` or `Any.hashCode`, so `==` is reference equality and two
 * definitions are equal exactly when they represent the same schema element. For every definition
 * `d` reachable from this schema, `type(d.typeName) == d` when `d` is a [Type]; the corresponding
 * owner map contains `d` by its declared name for nested definitions. Every [TypeExpr.baseType]
 * reachable from the schema is likewise the canonical result of [type].
 *
 * Construct every [Value.Object], [Value.Arguments], and [ObjectEngineResult.Key] through a factory
 * on its precise semantic category. The one-schema world
 * stipulates that every definition supplied to those factories is canonical in this schema; the
 * factories do not revalidate that ownership. Nested definitions navigate to their canonical owners through
 * [OutputField.containingType] and [InputLikeField.containingType]. Compare definitions with ordinary
 * `==`, `!=`, and collection equality operations. Only acyclic value objects such as [TypeExpr] and
 * [Value.Default] add structural equality over their properties.
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
     * Returns the canonical object field at [typeName]/[fieldName].
     *
     * @throws MissingSchemaElementException when the coordinate does not identify a field on a
     * concrete object type
     */
    fun objectField(
        typeName: String,
        fieldName: String,
    ): ObjectField {
        val containingType = type(typeName)
        if (containingType !is ObjectType) {
            throw MissingSchemaElementException(typeName, fieldName)
        }
        return containingType.fields[fieldName]
            ?: throw MissingSchemaElementException(typeName, fieldName)
    }

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
     * - `f.typeExpr == TypeExpr.Named.of(StringType, isNullable = false)`;
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
    sealed interface ScalarType : SimpleType

    data object IntType : ScalarType {
        override val typeName: String = "Int"
    }

    data object FloatType : ScalarType {
        override val typeName: String = "Float"
    }

    data object StringType : ScalarType {
        override val typeName: String = "String"
    }

    data object BooleanType : ScalarType {
        override val typeName: String = "Boolean"
    }

    data object IDType : ScalarType {
        override val typeName: String = "ID"
    }

    /**
     * An enum whose [values] are exactly its finite map of legal GraphQL enum values by name.
     *
     * ### Invariant: schema-enum-values
     *
     * The map has no modeled order. Every value is canonical in the containing schema, its
     * [EnumValue.containingType] is this type, and each map key equals its value's [EnumValue.name].
     */
    interface EnumType : SimpleType {
        val values: Map<String, EnumValue>
    }

    /**
     * One canonical member of [containingType].
     *
     * Enum values use schema-canonical equality. Same-named values from different enum types are
     * distinct definitions.
     */
    interface EnumValue {
        val name: String
        val containingType: EnumType
    }

    /** A structurally equal GraphQL ID result value. */
    sealed interface ID {
        val value: String

        companion object {
            fun of(value: String): ID = IDImpl(value)
        }
    }

    /**
     * An object type.
     *
     * [fields] contains `__typename` and the object's effective fields after flattening type
     * extensions and inherited interface fields. Interface implementation relationships are
     * represented by the schema's relation operations rather than stored on this definition.
     */
    interface ObjectType : CompositeType {
        override val fields: Map<String, ObjectField>
    }

    /**
     * An interface type.
     *
     * [fields] contains `__typename` and the interface's effective fields after flattening type
     * extensions and inherited interface fields. Parent-interface and implementation
     * relationships are represented by the schema's relation operations rather than stored here.
     */
    interface InterfaceType : CompositeType

    /**
     * A union definition.
     *
     * ### Invariant: schema-union-fields
     *
     * Union membership is represented by [CompositeType.possibleTypes]. [fields] contains exactly
     * the `__typename` field.
     */
    interface UnionType : CompositeType

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
    interface InputObjectType : InputType, InputObjectLike {
        override val fields: Map<String, InputField>
    }

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
    sealed interface FieldArguments : InputObjectLike {
        interface NonEmpty : FieldArguments
    }

    /**
     * The unique argument definition for every output field that takes no arguments.
     *
     * ### Invariant: schema-no-arguments
     *
     * For every [OutputField] `f`, `f.arguments == NoArguments` exactly when `f` takes no
     * arguments.
     */
    data object NoArguments : FieldArguments {
        override val fields: Map<String, FieldArgument> = emptyMap()
    }

    /**
     * The canonical output field at [containingType]/[fieldName].
     *
     * ### Invariant: schema-output-field-coordinate
     *
     * `containingType.fields[fieldName] == this`, and [typeExpr]'s base type is canonical in the same
     * schema. [arguments] is the input-object-like definition of the complete argument tuple.
     */
    interface OutputField {
        val fieldName: String
        val containingType: CompositeType
        val typeExpr: TypeExpr<OutputType>
        val arguments: FieldArguments
    }

    /**
     * An output field owned by a concrete object type.
     *
     * Every output field whose [OutputField.containingType] is an [ObjectType] implements this
     * interface.
     */
    interface ObjectField : OutputField {
        override val containingType: ObjectType
    }

    /**
     * A field of an input-object-like definition.
     *
     * ### Invariant: schema-input-like-field-coordinate
     *
     * `containingType.fields[name] == this`, and [typeExpr]'s base type is canonical in the same
     * schema. [defaultValue], when present, is valid for [typeExpr].
     */
    sealed interface InputLikeField {
        val name: String
        val containingType: InputObjectLike
        val typeExpr: TypeExpr<InputType>
        val defaultValue: Value.Default

        val isRequired: Boolean
            get() = !typeExpr.isNullable && defaultValue == Value.Default.Absent
    }

    /** The canonical input-object field at [containingType]/[fieldName]. */
    interface InputField : InputLikeField {
        val fieldName: String
        override val containingType: InputObjectType
        override val name: String
            get() = fieldName
    }

    /**
     * The canonical argument named [argumentName] in [containingType].
     *
     * `containingType.fields[argumentName] == this`.
     */
    interface FieldArgument : InputLikeField {
        val argumentName: String
        override val containingType: FieldArguments
        override val name: String
            get() = argumentName
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

private data class IDImpl(
    override val value: String,
) : Schema.ID
