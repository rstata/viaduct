package model.testing

import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.parser.Parser
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLTypeUtil
import graphql.validation.ValidationErrorType
import graphql.validation.Validator
import java.util.Locale
import model.Schema
import model.SourceSchemaAdapter
import model.SelectionForest
import model.Value
import model.spec.SpecSelection
import model.spec.flatten

/**
 * Parses and validates external GraphQL fragment text against the unaugmented source schema.
 *
 * Decoded selections are mapped directly to canonical definitions in [schema]. Every node-valued
 * source field `foo { selections }` becomes
 * `foo_V_A_node { node { selections } }`. Synthetic definitions cannot be selected in source text.
 */
internal class GJSelectionParser(
    private val schema: GJSchema,
    private val variableValues: Map<String, Value.Input?>,
    private val variableField: Schema.ObjectField? = null,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private var effectiveVariableField = variableField

    fun selectionsFrom(fragment: String): Pair<Schema.CompositeType, SelectionForest> {
        val parsed = specSelectionsFrom(fragment)
        val selections = flatten(schema, parsed.nominalType, parsed.selections)
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
            decodeSelectionSet(definition.selectionSet, graphQLTypeCondition)
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
    ): List<SpecSelection> =
        selectionSet.selections.map { selection ->
            when (selection) {
                is Field -> decodeField(selection, typeInScope)
                is InlineFragment -> decodeInlineFragment(selection, typeInScope)
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
    ): SpecSelection.Field {
        require(field.directives.isEmpty()) {
            "Applied directives are deferred from the current spec-selection model"
        }
        val fieldDefinition =
            graphql.introspection.Introspection.getFieldDef(
                schema.graphQLSchema,
                typeInScope,
                field.name,
            )!!
        val suppliedArguments = field.arguments.associateBy { it.name }
        val arguments =
            fieldDefinition.arguments
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
        val subselections =
            field.selectionSet?.let { selectionSet ->
                val resultType =
                    GraphQLTypeUtil.unwrapAll(fieldDefinition.type) as GraphQLCompositeType
                decodeSelectionSet(selectionSet, resultType)
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
                ),
        )
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
