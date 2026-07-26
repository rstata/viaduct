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
 * Exactly one instance is fixed for a reasoning world. Dependency-injection composition scopes the
 * public [Assumptions] binding as a singleton; this does not make it a JVM-global value.
 */
interface Assumptions {
    /**
     * The canonical schema for this reasoning world.
     *
     * Every schema definition referenced by a model value interpreted in this world belongs to this
     * schema and satisfies the canonicality invariants documented by [Schema].
     */
    val schema: Schema

    /**
     * The known variable bindings for this reasoning world.
     *
     * A bound value may be null, representing GraphQL null, but a non-null bound value cannot
     * recursively contain a [Schema.VariableValue]. Variable-to-variable bindings, including nested
     * references and cycles, are therefore excluded. A missing entry denotes an unbound or unknown
     * variable. A variable may be bound to [Schema.ErrorValue] because providers or fields may fail.
     *
     * See [Schema.VariableValue] for how bindings affect conservative equality.
     */
    val variableValues: VariableBindings

    /**
     * The node and field resolvers fixed for this reasoning world.
     */
    val executorRegistry: ExecutorRegistry

    /**
     * Parses and validates one GraphQL named fragment against [schema].
     *
     * The fragment name is ignored. The result contains its canonical composite type condition
     * followed by the post-validation selections in its selection set.
     */
    fun selectionsFrom(fragment: String): Pair<Schema.CompositeType, List<SpecSelection>>

    companion object {
        /**
         * Constructs one reasoning world over an already constructed [schema].
         *
         * Every value in [bindings] must have been constructed by [schema].
         */
        @JvmStatic
        fun of(
            schema: GJSchema,
            bindings: Map<String, Schema.Value?>,
            executorRegistry: ExecutorRegistry = ExecutorRegistry.empty(schema),
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
            "Applied directives are outside the spec-selection model"
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
                        "Named fragment spreads are outside the spec-selection model",
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
            "Applied directives are outside the spec-selection model"
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
            fieldName = field.name,
            arguments = arguments,
            subselections = subselections,
        )
    }

    private fun decodeInlineFragment(
        fragment: InlineFragment,
        typeInScope: GraphQLCompositeType,
    ): SpecSelection.InlineFragment {
        require(fragment.directives.isEmpty()) {
            "Applied directives are outside the spec-selection model"
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
