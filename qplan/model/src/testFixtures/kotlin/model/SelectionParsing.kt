package model

import model.testing.GJSelectionParser
import model.testing.GJSchema

/** Parses one post-validation fragment as test-fixture preparation outside semantic model logic. */
fun Assumptions.selectionsFrom(fragment: String): Pair<Schema.CompositeType, SelectionForest> =
    selectionParser().selectionsFrom(fragment)

/** Parses one post-validation GraphQL fragment into the model fragment used by tests. */
fun Schema.fragmentFrom(
    source: String,
    bindings: Map<String, Value?> = emptyMap(),
): Fragment =
    GJSelectionParser(
        schema = this as GJSchema,
        variableValues = VariableBindings.from(bindings),
    ).fragmentFrom(source)

/** Parses one post-validation GraphQL fragment using this world's variable bindings. */
fun Assumptions.fragmentFrom(source: String): Fragment =
    selectionParser().fragmentFrom(source)

/** Constructs the model-only empty fragment that GraphQL text cannot express. */
fun Schema.emptyFragmentOf(typeName: String): Fragment =
    Fragment.of(
        nominalType = type(typeName) as Schema.CompositeType,
        subselections = selectionForestOf(),
    )

/** Constructs the model-only empty fragment that GraphQL text cannot express. */
fun Assumptions.emptyFragmentOf(typeName: String): Fragment =
    schema.emptyFragmentOf(typeName)

private fun Assumptions.selectionParser(): GJSelectionParser =
    GJSelectionParser(
        schema = schema as GJSchema,
        variableValues = variableValues,
    )

private fun GJSelectionParser.fragmentFrom(source: String): Fragment {
    val (nominalType, selections) = selectionsFrom(source)
    return Fragment.of(nominalType, selections)
}
