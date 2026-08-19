package model

import graphql.schema.GraphQLObjectType

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
 * `d` reachable from this schema, `types[d.name] == d` when `d` is a [TypeDef]; the corresponding
 * owner collection contains `d` by its declared name for nested definitions. Every [TypeExpr.baseType]
 * reachable from the schema is likewise the canonical result of [types].
 *
 * Construct every [Arguments.Resolved] and [ObjectEngineResult.Key] through a factory on its precise
 * semantic category, and every engine object through qplan's validating EOD factory. The one-schema world
 * stipulates that every definition supplied to those factories is canonical in this schema; the
 * factories do not revalidate that ownership. Nested definitions navigate to their canonical owners through
 * [Field.containingDef] and [InputLikeField.containingDef]. Compare definitions with ordinary
 * `==`, `!=`, and collection equality operations. Only acyclic value objects such as [TypeExpr] and
 * [CoercedDefaultValue] add structural equality over their properties.
 *
 * ### Invariant: schema-supported-domain
 *
 * [queryTypeDef] is the canonical `Query` [Object] and is always present. The only permitted scalar
 * definitions are the five [Scalar] singletons [IntType], [FloatType], [StringType], [BooleanType],
 * and [IDType]; whenever one belongs to this schema, [types] contains that singleton.
 *
 * Type-extension declarations, their boundaries, and their provenance are not represented; their
 * merged effects are already present in the effective field collections. Directives, descriptions, source
 * locations, source-schema introspection fields, custom scalars, mutations, and subscriptions
 * are outside the current model.
 * Collections exposed by the schema are finite mathematical collections and sets; their iteration order,
 * concrete implementation, and mutability are not modeled.
 */
interface Schema {
    /** All canonical type definitions, keyed by [TypeDef.name]. */
    val types: Map<String, TypeDef>

    /**
     * The canonical query root.
     *
     * ### Invariant: schema-query-root
     *
     * This model requires a query root, so [requireQueryTypeDef] always succeeds for a decoded
     * schema. The nullable shape matches ViaductSchema.
     */
    val queryTypeDef: Object?

    /**
     * A named type definition.
     *
     * ### Invariant: schema-type-name-uniqueness
     *
     * Definitions use the canonical equality documented by [Schema]: `a == b` exactly when `a` and
     * `b` represent the same schema type. Equivalently, two type definitions have the same
     * [name] exactly when they are equal. The permitted concrete categories are exhaustively
     * scalar, enum, object, interface, union, or input object.
     */
    sealed interface TypeDef {
        val name: String
    }

    /**
     * A type permitted as the base type of a GraphQL input value.
     *
     * The input types are exactly scalars, enums, and input objects.
     */
    sealed interface InputTypeDef : TypeDef

    /**
     * A type permitted as the base type of a GraphQL output value.
     *
     * The output types are exactly scalars, enums, objects, interfaces, and unions.
     */
    sealed interface OutputTypeDef : TypeDef

    /** Exactly the scalar and enum types, which are both input and output types. */
    sealed interface SimpleTypeDef : InputTypeDef, OutputTypeDef

    /**
     * A type on which GraphQL selection sets and type conditions are meaningful.
     *
     * ### Invariant: schema-composite-field-graph
     *
     * [fields] contains all canonical lowered fields selectable at this type, rather than only
     * fields declared in source SDL. Every source object and interface type `t`, together with the
     * synthetic `V_I_Top` interface, has exactly one owner-specific synthetic field `f` for which:
     *
     * - `f.name == "V_I_typename"`;
     * - `f.containingDef == t`;
     * - `f.type == TypeExpr.Named.of(StringType, isNullable = false)`;
     * - `f.arguments == NoArguments`; and
     * - `t.field("V_I_typename") == f`.
     *
     * Unions own no fields. Synthetic node-bridge objects own only their `id` and `node` fields.
     * The synthetic interface `V_I_Top` owns the field selected for union-scoped source
     * `__typename` and has every lowered source object, but no node bridge, in [possibleObjectTypes].
     *
     * Each field's [Field.containingDef] is this definition. Conversely, every [Field] in the
     * schema occurs exactly once in its containing definition's map. Flattened copies at different
     * schema coordinates are distinct canonical definitions even when their signatures match.
     * Effective object and interface fields satisfy GraphQL interface-field compatibility for every
     * interface they implement.
     */
    sealed interface CompositeTypeDef : OutputTypeDef {
        val fields: Collection<Field>

        fun field(name: String): Field? = fields.find { it.name == name }

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
        val possibleObjectTypes: Set<Object>
    }

    /**
     * A scalar in the model's fixed universe of built-in GraphQL scalar types.
     *
     * ### Invariant: schema-scalar-universe
     *
     * The instances are exactly [IntType], [FloatType], [StringType], [BooleanType], and [IDType],
     * and their [TypeDef.name] values are fixed by those declarations. Any scalar reachable from
     * this schema is the corresponding singleton.
     */
    sealed interface Scalar : SimpleTypeDef

    data object IntType : Scalar {
        override val name: String = "Int"
    }

    data object FloatType : Scalar {
        override val name: String = "Float"
    }

    data object StringType : Scalar {
        override val name: String = "String"
    }

    data object BooleanType : Scalar {
        override val name: String = "Boolean"
    }

    data object IDType : Scalar {
        override val name: String = "ID"
    }

    /**
     * An enum whose [values] are exactly its finite collection of legal GraphQL enum values.
     *
     * ### Invariant: schema-enum-values
     *
     * Every value is canonical in the containing schema and its [EnumValue.containingDef] is this
     * type.
     */
    interface Enum : SimpleTypeDef {
        val values: Collection<EnumValue>

        fun value(name: String): EnumValue? = values.find { it.name == name }
    }

    /**
     * One canonical member of [containingDef].
     *
     * Enum values use schema-canonical equality. Same-named values from different enum types are
     * distinct definitions.
     */
    interface EnumValue {
        val name: String
        val containingDef: Enum
    }

    /**
     * An object type.
     *
     * For source objects, [fields] contains `V_I_typename` and the object's effective fields after
     * flattening type extensions and inherited interface fields. Synthetic node-bridge objects
     * contain only their `id` and `node` fields. Interface implementation relationships are
     * represented by the schema's relation operations rather than stored on this definition.
     *
     * [graphQLJavaDefinition] is a canonical opaque attachment for integration with Viaduct engine
     * APIs. It is not part of the mathematical schema model and must not be inspected for schema
     * reasoning, equality, hashing, conformance, or subtype decisions.
     */
    interface Object : CompositeTypeDef {
        override val fields: Collection<ObjectField>
        override fun field(name: String): ObjectField? = fields.find { it.name == name }

        /**
         * The canonical GraphQL-Java definition representing this lowered object type.
         *
         * Repeated reads return the same instance within one schema. Semantic logic treats this as
         * an opaque foreign value; only integration boundaries may unwrap or pass it through.
         */
        val graphQLJavaDefinition: GraphQLObjectType
    }

    /**
     * An interface type.
     *
     * [fields] contains `V_I_typename` and the interface's effective fields after flattening type
     * extensions and inherited interface fields. Parent-interface and implementation
     * relationships are represented by the schema's relation operations rather than stored here.
     */
    interface Interface : CompositeTypeDef

    /**
     * A union definition.
     *
     * ### Invariant: schema-union-fields
     *
     * Union membership is represented by [CompositeTypeDef.possibleObjectTypes]. [fields] is empty.
     */
    interface Union : CompositeTypeDef

    /**
     * A schema definition shaped like a GraphQL input object.
     *
     * ### Invariant: schema-input-field-graph
     *
     * The instances are named [Input] definitions and schema-synthetic [FieldArguments]
     * definitions. Each field's [InputLikeField.containingDef] is this definition.
     */
    sealed interface InputObjectLike {
        val fields: Collection<InputLikeField>

        fun field(name: String): InputLikeField? = fields.find { it.name == name }
    }

    /**
     * An input object definition.
     *
     * Every [InputField] occurs exactly once in its containing definition's field collection.
     */
    interface Input : InputTypeDef, InputObjectLike {
        override val fields: Collection<InputField>
        override fun field(name: String): InputField? = fields.find { it.name == name }
    }

    /**
     * The complete argument definition of an output field.
     *
     * ### Invariant: schema-field-argument-graph
     *
     * This is schema-synthetic rather than a named [TypeDef], and it cannot occur in a [TypeExpr].
     * Each non-empty instance belongs to exactly one [Field]. The empty argument definition
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
     * For every [Field] `f`, `f.arguments == NoArguments` exactly when `f` takes no
     * arguments.
     */
    data object NoArguments : FieldArguments {
        override val fields: Collection<FieldArg> = emptyList()
    }

    /**
     * The canonical output field at [containingDef]/[name].
     *
     * ### Invariant: schema-output-field-coordinate
     *
     * `containingDef.field(name) == this`, and [type]'s base type is canonical in the same
     * schema. [arguments] is the input-object-like definition of the complete argument tuple.
     */
    interface Field {
        val name: String
        val containingDef: CompositeTypeDef
        val type: TypeExpr<OutputTypeDef>
        val arguments: FieldArguments
    }

    /**
     * An output field owned by a concrete object type.
     *
     * Every output field whose [Field.containingDef] is an [Object] implements this
     * interface.
     */
    interface ObjectField : Field {
        override val containingDef: Object
    }

    /**
     * A field of an input-object-like definition.
     *
     * ### Invariant: schema-input-like-field-coordinate
     *
     * `containingDef.field(name) == this`, and [type]'s base type is canonical in the same
     * schema. [defaultValue], when present, is valid for [type].
     */
    sealed interface InputLikeField {
        val name: String
        val containingDef: InputObjectLike
        val type: TypeExpr<InputTypeDef>
        val defaultValue: CoercedDefaultValue

        val isRequired: Boolean
            get() = !type.isNullable && defaultValue == CoercedDefaultValue.Absent
    }

    /** The canonical input-object field at [containingDef]/[name]. */
    interface InputField : InputLikeField {
        override val containingDef: Input
    }

    /**
     * The canonical argument named [name] in [containingDef].
     *
     * `containingDef.field(name) == this`.
     */
    interface FieldArg : InputLikeField {
        override val containingDef: FieldArguments
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
