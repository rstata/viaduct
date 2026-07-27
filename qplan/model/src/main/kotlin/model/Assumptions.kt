package model

import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import java.util.Locale
import model.registry.ExecutorRegistry
import model.spec.SpecSelection

/**
 * The fixed schema, bindings, and executors under which model values and operations are interpreted.
 *
 * ### Invariant: world-single-schema
 *
 * Exactly one instance is fixed for a reasoning world. Dependency-injection composition scopes the
 * public [Assumptions] binding as a singleton; this does not make it a JVM-global value.
 */
interface Assumptions {
    /**
     * The canonical schema for this reasoning world.
     *
     * ### Invariant: world-canonical-ownership
     *
     * Every schema definition referenced by a model value interpreted in this world belongs to this
     * schema and satisfies the canonicality invariants documented by [Schema].
     */
    val schema: Schema

    /**
     * The known variable bindings for this reasoning world.
     *
     * ### Invariant: world-variable-name-consistency
     *
     * Variable names are unique and non-conflicting throughout the world, so equal names denote the
     * same eventual value.
     *
     * ### Binding Semantics
     *
     * A bound value may be null, representing GraphQL null, and a missing entry denotes an unbound
     * or unknown variable. A variable may be bound to [Schema.ErrorValue] because providers or
     * fields may fail. [VariableBindings] documents the constraints on bound values.
     *
     * See [Schema.VariableValue] for how bindings affect conservative equality.
     */
    val variableValues: VariableBindings

    /**
     * The node and field resolvers fixed for this reasoning world.
     *
     * ### Invariant: world-executor-schema
     *
     * Every definition and model value referenced by the registry belongs to [schema].
     *
     * ### Resolver Interpretation
     *
     * Registered functions contain the selection-independent inputs of resolver interpretation.
     * Requested selections are applied separately by projecting their object results with `snip`.
     */
    val executorRegistry: ExecutorRegistry

    /**
     * Whether resolution of [field] crosses a resolver behavior boundary.
     *
     * This predicate is defined only for a canonical field whose containing type is a concrete
     * [Schema.ObjectType]. It is true for the engine-supplied `__typename` field, when the field has
     * an explicit field resolver, or when its containing type has a node resolver and the field is
     * not `id`. The node `id` field is materialized by the resolver that produced the node
     * reference.
     *
     * @throws IllegalArgumentException when [field] is abstract or foreign to [schema]
     */
    fun behavioral(field: Schema.OutputField): Boolean

    /**
     * Parses and validates one GraphQL named fragment against [schema].
     *
     * The named fragment is a parsing envelope: its name is ignored, and named fragment spreads
     * within its selection set must already have been inlined. Applied directives are temporarily
     * unsupported. The result contains its canonical composite type condition followed by the
     * post-validation selections in its selection set.
     */
    fun selectionsFrom(fragment: String): Pair<Schema.CompositeType, List<SpecSelection>>

    companion object {
        /**
         * Constructs one reasoning world over an already constructed [schema].
         *
         * Every non-error value in [bindings] must have been constructed by [schema].
         * [Schema.ErrorValue] is schema-independent.
         */
        @JvmStatic
        fun of(
            schema: GJSchema,
            bindings: Map<String, Schema.Value?>,
            executorRegistry: ExecutorRegistry,
        ): Assumptions = DefaultAssumptions(schema, bindings, executorRegistry)
    }
}

/**
 * Assumptions over an already constructed [GJSchema], model variable values, and executor registry.
 *
 * Every schema definition carried by the supplied bindings must be the canonical definition from
 * [schema]. The executor registry is stipulated to belong to that same schema. The supplied schema,
 * valid binding values, and registry are retained rather than decoded or rebased.
 */
private class DefaultAssumptions(
    override val schema: GJSchema,
    bindings: Map<String, Schema.Value?>,
    override val executorRegistry: ExecutorRegistry,
) : Assumptions {
    private val graphQLSchema: GraphQLSchema = schema.graphQLSchema

    override val variableValues =
        VariableBindings.from(schema, bindings)

    override fun behavioral(field: Schema.OutputField): Boolean {
        val containingType = field.containingType
        require(containingType is Schema.ObjectType) {
            "Behavioral is defined only for fields on concrete object types"
        }
        require(schema.field(containingType.typeName, field.fieldName) == field) {
            "${containingType.typeName}/${field.fieldName} is not canonical in this world"
        }
        return field.fieldName == "__typename" ||
            executorRegistry.hasFieldResolver(field) ||
            (
                executorRegistry.hasNodeResolver(containingType) &&
                    field.fieldName != "id"
            )
    }

    override fun selectionsFrom(
        fragment: String,
    ): Pair<Schema.CompositeType, List<SpecSelection>> {
        val document = Parser.parse(fragment)
        val definition =
            document.definitions.singleOrNull() as? FragmentDefinition
                ?: throw IllegalArgumentException(
                    "Expected exactly one named fragment definition",
                )
        require(definition.directives.isEmpty()) {
            "Applied directives are deferred from the current spec-selection model"
        }
        validateFragment(document)

        val typeConditionName = definition.typeCondition.name!!
        val typeCondition = schema.type(typeConditionName) as Schema.CompositeType
        val graphQLTypeCondition =
            graphQLSchema.getType(typeConditionName) as GraphQLCompositeType

        return typeCondition to
            decodeSelectionSet(
                selectionSet = definition.selectionSet,
                typeInScope = graphQLTypeCondition,
            )
    }

    private fun validateFragment(document: Document) {
        val errors =
            Validator()
                .validateDocument(graphQLSchema, document, Locale.ENGLISH)
                .filterNot { it.validationErrorType in STANDALONE_FRAGMENT_ERRORS }
        require(errors.isEmpty()) {
            errors.joinToString(
                prefix = "Invalid GraphQL fragment: ",
                separator = "; ",
            ) { it.message }
        }
    }

    private fun decodeSelectionSet(
        selectionSet: SelectionSet,
        typeInScope: GraphQLCompositeType,
    ): List<SpecSelection> =
        selectionSet.selections.map { selection ->
            when (selection) {
                is Field -> decodeField(selection, typeInScope)
                is InlineFragment -> decodeInlineFragment(selection, typeInScope)
                is FragmentSpread ->
                    throw IllegalArgumentException(
                        "Named fragment spreads must be inlined before constructing spec selections",
                    )

                else ->
                    throw IllegalArgumentException(
                        "Unexpected GraphQL selection: $selection",
                    )
            }
        }

    private fun decodeField(
        field: Field,
        typeInScope: GraphQLCompositeType,
    ): SpecSelection.Field {
        require(field.directives.isEmpty()) {
            "Applied directives are deferred from the current spec-selection model"
        }
        val fieldDefinition =
            graphql.introspection.Introspection.getFieldDef(
                graphQLSchema,
                typeInScope,
                field.name,
            )!!
        val suppliedArguments = field.arguments.associateBy { it.name }
        val arguments =
            buildMap<String, Schema.InputValue?> {
                fieldDefinition.arguments.forEach { argumentDefinition ->
                    val suppliedArgument = suppliedArguments[argumentDefinition.name]
                    when {
                        suppliedArgument != null ->
                            put(
                                argumentDefinition.name,
                                decodeLiteral(
                                    type = argumentDefinition.type,
                                    value = suppliedArgument.value,
                                    variableValues = variableValues,
                                    schema = schema,
                                ),
                            )

                        argumentDefinition.hasSetDefaultValue() ->
                            put(
                                argumentDefinition.name,
                                decodeInputValue(
                                    argumentDefinition.type,
                                    argumentDefinition.argumentDefaultValue,
                                    variableValues,
                                    schema,
                                ),
                            )
                    }
                }
            }

        val subselections =
            field.selectionSet?.let { selectionSet ->
                val resultType =
                    GraphQLTypeUtil.unwrapAll(fieldDefinition.type) as GraphQLCompositeType
                decodeSelectionSet(selectionSet, resultType)
            }

        return SpecSelection.Field.of(
            alias = field.alias,
            field = schema.field(typeInScope.name, field.name),
            arguments = arguments,
            subselections = subselections,
        )
    }

    private fun decodeInlineFragment(
        fragment: InlineFragment,
        typeInScope: GraphQLCompositeType,
    ): SpecSelection.InlineFragment {
        require(fragment.directives.isEmpty()) {
            "Applied directives are deferred from the current spec-selection model"
        }
        val typeConditionName = fragment.typeCondition?.name
        val graphQLTypeCondition =
            typeConditionName?.let { graphQLSchema.getType(it) as GraphQLCompositeType }
        val modelTypeCondition =
            typeConditionName?.let { schema.type(it) as Schema.CompositeType }

        return SpecSelection.InlineFragment.of(
            typeCondition = modelTypeCondition,
            selections =
                decodeSelectionSet(
                    selectionSet = fragment.selectionSet,
                    typeInScope = graphQLTypeCondition ?: typeInScope,
                ),
        )
    }
}

// A standalone fragment is unused and has no operation variable definitions. Variable references
// are instead interpreted through the supplied VariableBindings.
private val STANDALONE_FRAGMENT_ERRORS =
    setOf(
        ValidationErrorType.UnusedFragment,
        ValidationErrorType.UndefinedVariable,
    )
