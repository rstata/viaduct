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
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import jakarta.inject.Inject
import java.util.Locale
import model.spec.SpecSelection

/**
 * Global assumptions constructed from validated GraphQL SDL and model variable values.
 */
class GJAssumptions
    @Inject
    constructor(
        @SchemaSDL schemaSDL: String,
        @VariableValues bindings: Map<String, GraphQLValue?>,
    ) : GlobalAssumptions {
        override val variableValues = VariableBindings.from(bindings)

        private val graphQLSchema: GraphQLSchema = parseSchema(schemaSDL)

        override val schema: Schema =
            GJSchemaDecoder(graphQLSchema, variableValues).decode()

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
                buildMap<String, GraphQLInputValue?> {
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
                                    ),
                                )

                            argumentDefinition.hasSetDefaultValue() ->
                                put(
                                    argumentDefinition.name,
                                    decodeInputValue(
                                        argumentDefinition.type,
                                        argumentDefinition.argumentDefaultValue,
                                        variableValues,
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
            val graphQLTypeCondition =
                fragment.typeCondition?.let { typeCondition ->
                    graphQLSchema.getType(typeCondition.name!!) as GraphQLCompositeType
                }
            val modelTypeCondition =
                fragment.typeCondition?.let { typeCondition ->
                    schema.type(typeCondition.name!!) as Schema.CompositeType
                }

            return SpecSelection.InlineFragment.of(
                typeCondition = modelTypeCondition,
                selections =
                    decodeSelectionSet(
                        selectionSet = fragment.selectionSet,
                        typeInScope = graphQLTypeCondition ?: typeInScope,
                    ),
            )
        }

        private companion object {
            val STANDARD_SCALAR_NAMES = setOf("Int", "Float", "String", "Boolean", "ID")
            val STANDARD_DIRECTIVE_NAMES =
                setOf("skip", "include", "deprecated", "specifiedBy", "oneOf")

            @JvmStatic
            private fun parseSchema(schemaSDL: String): GraphQLSchema {
                val registry = SchemaParser().parse(schemaSDL)
                val nonStandardScalars =
                    (
                        registry.scalars().keys +
                            registry.scalarTypeExtensions().keys
                    ) - STANDARD_SCALAR_NAMES
                require(nonStandardScalars.isEmpty()) {
                    "Non-standard scalar types are outside the model: " +
                        nonStandardScalars.sorted().joinToString()
                }

                val nonStandardDirectives =
                    registry.directiveDefinitions.keys - STANDARD_DIRECTIVE_NAMES
                require(nonStandardDirectives.isEmpty()) {
                    "Non-standard directives are outside the model: " +
                        nonStandardDirectives.sorted().joinToString()
                }

                return UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
            }
        }
    }

// A standalone fragment is unused and has no operation variable definitions. Variable references
// are instead interpreted through the supplied VariableBindings.
private val STANDALONE_FRAGMENT_ERRORS =
    setOf(
        ValidationErrorType.UnusedFragment,
        ValidationErrorType.UndefinedVariable,
    )
