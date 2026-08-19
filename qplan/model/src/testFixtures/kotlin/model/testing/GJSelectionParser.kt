package model.testing

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ValuesResolver
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.introspection.Introspection
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLTypeUtil
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import java.util.Locale
import model.EngineInputData
import model.MaterializeSelectionForest
import model.Schema
import model.SourceSchemaAdapter
import model.SelectionForest
import model.spec.SpecSelection
import model.spec.flatten
import model.spec.flattenForMaterialization

/**
 * Parses and validates external GraphQL fragment text against the unaugmented source schema.
 *
 * Decoded selections are mapped directly to canonical definitions in [schema]. Every node-valued
 * source field `foo { selections }` becomes
 * `foo_V_A_node { node { selections } }`. Synthetic definitions cannot be selected in source text.
 */
internal class GJSelectionParser(
    private val schema: GJSchema,
    private val variableValues: Map<String, EngineInputData?>,
    private val variableField: Schema.ObjectField? = null,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private var effectiveVariableField = variableField

    fun selectionsFrom(fragment: String): Pair<Schema.CompositeType, SelectionForest> {
        val parsed = specSelectionsFrom(fragment)
        val selections = flatten(schema, parsed.nominalType, parsed.selections)
        return parsed.nominalType to selections
    }

    fun selectionsFrom(
        operation: OperationDefinition,
        variables: CoercedVariables,
        graphQLContext: GraphQLContext,
        locale: Locale,
    ): SelectionForest {
        require(operation.operation == OperationDefinition.Operation.QUERY) {
            "Qplan operation decoding supports query operations only"
        }
        require(operation.directives.isEmpty()) {
            "Applied directives are deferred from the current spec-selection model"
        }
        val selections =
            decodeSelectionSet(
                selectionSet = operation.selectionSet,
                typeInScope = schema.graphQLSchema.queryType,
                argumentDecoder =
                    CoercedArgumentDecoder(
                        variables = variables,
                        graphQLContext = graphQLContext,
                        locale = locale,
                    ),
            )
        return flatten(schema, schema.query, selections)
    }

    fun materializeSelectionsFrom(
        fragment: String,
    ): Pair<Schema.CompositeType, MaterializeSelectionForest> {
        val parsed = specSelectionsFrom(fragment)
        val selections =
            flattenForMaterialization(schema, parsed.nominalType, parsed.selections)
        return parsed.nominalType to selections
    }

    fun specSelectionsFrom(fragment: String): ParsedSpecFragment {
        val document = Parser.parse(fragment)
        val definition =
            document.definitions.singleOrNull() as? FragmentDefinition
                ?: throw IllegalArgumentException("Expected exactly one named fragment definition")
        require(definition.directives.isEmpty()) {
            "Applied directives are deferred from the current spec-selection model"
        }
        validateFragment(document)

        val typeConditionName = definition.typeCondition.name!!
        val typeCondition = schema.type(typeConditionName) as Schema.CompositeType
        if (effectiveVariableField == null) {
            effectiveVariableField =
                typeCondition.possibleTypes
                    .first()
                    .fields
                    .values
                    .first()
        }
        val graphQLTypeCondition =
            schema.graphQLSchema.getType(typeConditionName) as GraphQLCompositeType
        val specSelections =
            decodeSelectionSet(
                selectionSet = definition.selectionSet,
                typeInScope = graphQLTypeCondition,
                argumentDecoder = LiteralArgumentDecoder(),
            )
        return ParsedSpecFragment(typeCondition, specSelections)
    }

    private fun validateFragment(document: Document) {
        val errors =
            Validator()
                .validateDocument(schema.graphQLSchema, document, Locale.ENGLISH)
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
        argumentDecoder: ArgumentDecoder,
    ): List<SpecSelection> =
        selectionSet.selections.map { selection ->
            when (selection) {
                is Field -> decodeField(selection, typeInScope, argumentDecoder)
                is InlineFragment ->
                    decodeInlineFragment(
                        fragment = selection,
                        typeInScope = typeInScope,
                        argumentDecoder = argumentDecoder,
                    )
                is FragmentSpread ->
                    throw IllegalArgumentException(
                        "Named fragment spreads must be inlined before constructing spec selections",
                    )
                else -> throw IllegalArgumentException("Unexpected GraphQL selection: $selection")
            }
        }

    private fun decodeField(
        field: Field,
        typeInScope: GraphQLCompositeType,
        argumentDecoder: ArgumentDecoder,
    ): SpecSelection.Field {
        require(field.directives.isEmpty()) {
            "Applied directives are deferred from the current spec-selection model"
        }
        val fieldDefinition =
            Introspection.getFieldDef(
                schema.graphQLSchema,
                typeInScope,
                field.name,
            )!!
        val arguments = argumentDecoder.decode(field, fieldDefinition)
        val subselections =
            field.selectionSet?.let { selectionSet ->
                val resultType =
                    GraphQLTypeUtil.unwrapAll(fieldDefinition.type) as GraphQLCompositeType
                decodeSelectionSet(selectionSet, resultType, argumentDecoder)
            }
        val canonicalField = sourceSchema.field(typeInScope.name, field.name)
        val loweredNodeField = schema.isLoweredNodeField(canonicalField)
        val canonicalSubselections =
            if (loweredNodeField) {
                val bridgeType = canonicalField.typeExpr.baseType as Schema.ObjectType
                val payloadField =
                    schema.objectField(bridgeType.typeName, NODE_BRIDGE_PAYLOAD_FIELD)
                listOf(
                    SpecSelection.Field.of(
                        alias = null,
                        field = payloadField,
                        arguments = emptyMap(),
                        subselections = subselections,
                    ),
                )
            } else {
                subselections
            }
        return SpecSelection.Field.of(
            alias = field.alias ?: field.name.takeIf { loweredNodeField },
            field = canonicalField,
            arguments = arguments,
            subselections = canonicalSubselections,
        )
    }

    private fun decodeInlineFragment(
        fragment: InlineFragment,
        typeInScope: GraphQLCompositeType,
        argumentDecoder: ArgumentDecoder,
    ): SpecSelection.InlineFragment {
        require(fragment.directives.isEmpty()) {
            "Applied directives are deferred from the current spec-selection model"
        }
        val typeConditionName = fragment.typeCondition?.name
        val graphQLTypeCondition =
            typeConditionName?.let {
                schema.graphQLSchema.getType(it) as GraphQLCompositeType
            }
        val modelTypeCondition =
            typeConditionName?.let { schema.type(it) as Schema.CompositeType }
        return SpecSelection.InlineFragment.of(
            typeCondition = modelTypeCondition,
            selections =
                decodeSelectionSet(
                    fragment.selectionSet,
                    graphQLTypeCondition ?: typeInScope,
                    argumentDecoder,
                ),
        )
    }

    private sealed interface ArgumentDecoder {
        fun decode(
            field: Field,
            fieldDefinition: GraphQLFieldDefinition,
        ): Map<String, Any?>
    }

    private inner class LiteralArgumentDecoder : ArgumentDecoder {
        override fun decode(
            field: Field,
            fieldDefinition: GraphQLFieldDefinition,
        ): Map<String, Any?> {
            val suppliedArguments = field.arguments.associateBy { it.name }
            return fieldDefinition.arguments
                .mapNotNull { argumentDefinition ->
                    val suppliedArgument = suppliedArguments[argumentDefinition.name]
                    when {
                        suppliedArgument != null ->
                            argumentDefinition.name to
                                decodeLiteral(
                                    type = argumentDefinition.type,
                                    value = suppliedArgument.value,
                                    variableValues = variableValues,
                                    schema = schema,
                                    variableField = effectiveVariableField,
                                )
                        argumentDefinition.hasSetDefaultValue() ->
                            argumentDefinition.name to
                                decodeInputValue(
                                    argumentDefinition.type,
                                    argumentDefinition.argumentDefaultValue,
                                    variableValues,
                                    schema,
                                    effectiveVariableField,
                                )
                        else -> null
                    }
                }.toMap()
        }
    }

    private inner class CoercedArgumentDecoder(
        private val variables: CoercedVariables,
        private val graphQLContext: GraphQLContext,
        private val locale: Locale,
    ) : ArgumentDecoder {
        override fun decode(
            field: Field,
            fieldDefinition: GraphQLFieldDefinition,
        ): Map<String, Any?> {
            val values =
                ValuesResolver.getArgumentValues(
                    schema.graphQLSchema.codeRegistry,
                    fieldDefinition.arguments,
                    field.arguments,
                    variables,
                    graphQLContext,
                    locale,
                )
            return fieldDefinition.arguments
                .mapNotNull { argumentDefinition ->
                    if (argumentDefinition.name !in values) {
                        null
                    } else {
                        argumentDefinition.name to
                            decodeExternalInputValue(
                                type = argumentDefinition.type,
                                value = values[argumentDefinition.name],
                                schema = schema,
                            )
                    }
                }.toMap()
        }
    }

    private companion object {
        val STANDALONE_FRAGMENT_ERRORS =
            setOf(
                ValidationErrorType.UnusedFragment,
                ValidationErrorType.UndefinedVariable,
            )
    }
}

internal data class ParsedSpecFragment(
    val nominalType: Schema.CompositeType,
    val selections: List<SpecSelection>,
)
