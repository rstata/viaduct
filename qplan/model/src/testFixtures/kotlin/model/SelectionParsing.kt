package model

import model.testing.GJSelectionParser
import model.testing.GJSchema

/** Parses one post-validation fragment as test-fixture preparation outside semantic model logic. */
fun Assumptions.selectionsFrom(fragment: String): Pair<Schema.CompositeType, SelectionForest> =
    schema.selectionParser().selectionsFrom(fragment)

/** Parses one post-validation GraphQL fragment into the model fragment used by tests. */
fun Schema.fragmentFrom(
    source: String,
    bindings: Map<String, Value?> = emptyMap(),
    variableField: Schema.ObjectField? = null,
): Fragment =
    GJSelectionParser(
        schema = this as GJSchema,
        variableValues = bindings.validatedVariableValues(),
        variableField = variableField,
    ).fragmentFrom(source)

/** Parses one post-validation GraphQL fragment without operation-variable bindings. */
fun Assumptions.fragmentFrom(source: String): Fragment =
    schema.fragmentFrom(source)

/** Constructs the model-only empty fragment that GraphQL text cannot express. */
fun Schema.emptyFragmentOf(typeName: String): Fragment =
    Fragment.of(
        nominalType = type(typeName) as Schema.CompositeType,
        subselections = selectionForestOf(),
    )

/** Constructs the model-only empty fragment that GraphQL text cannot express. */
fun Assumptions.emptyFragmentOf(typeName: String): Fragment =
    schema.emptyFragmentOf(typeName)

private fun Schema.selectionParser(): GJSelectionParser =
    GJSelectionParser(
        schema = this as GJSchema,
        variableValues = emptyMap(),
    )

private fun GJSelectionParser.fragmentFrom(source: String): Fragment {
    val (nominalType, selections) = selectionsFrom(source)
    return Fragment.of(nominalType, selections)
}

private fun Map<String, Value?>.validatedVariableValues(): Map<String, Value.Input?> =
    mapValues { (variableName, value) ->
        require(value == null || value is Value.Input) {
            "Variable $variableName contains a non-input GraphQL value"
        }
        require(value == null || !value.containsVariable()) {
            "Variable $variableName contains an unresolved variable"
        }
        value as Value.Input?
    }

private fun Value.containsVariable(): Boolean =
    when (this) {
        Value.Error -> false
        is Value.Variable -> true
        is Value.InputList -> values.any { it?.containsVariable() == true }
        is Value.InputObject -> fieldValues.values.any { it?.containsVariable() == true }
        else -> false
    }
