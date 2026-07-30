package model.spec

import model.Assumptions
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.selectionForestOf

/**
 * Flattens a spec selection set interpreted with [typeInScope].
 *
 * Fields become [Selection] values, while inline fragments are removed after contributing their
 * type conditions to the fields they contain. Each source field occurrence contributes one member
 * to the returned [SelectionForest], which erases source order while preserving occurrences and
 * does not define semantic equality for [Selection].
 */
context(world: Assumptions)
fun flatten(
    typeInScope: Schema.CompositeType,
    selectionSet: List<SpecSelection>,
): SelectionForest {
    val initialContext =
        SelectionContext(
            nominalType = typeInScope,
            possibleTypes = typeInScope.possibleTypes,
        )
    return flattenSelectionSet(selectionSet, initialContext)
}

context(world: Assumptions)
private fun flattenSelectionSet(
    selections: List<SpecSelection>,
    context: SelectionContext,
): SelectionForest =
    selections.fold(selectionForestOf()) { result, selection ->
        result +
            when (selection) {
                is SpecSelection.Field -> selectionForestOf(selection.flattenField(context))
                is SpecSelection.InlineFragment -> {
                    val fragmentContext =
                        selection.typeCondition?.let { typeCondition ->
                            SelectionContext(
                                nominalType = typeCondition,
                                possibleTypes =
                                    context.possibleTypes intersect typeCondition.possibleTypes,
                            )
                        } ?: context
                    flattenSelectionSet(selection.selections, fragmentContext)
                }
            }
    }

context(world: Assumptions)
private fun SpecSelection.Field.flattenField(context: SelectionContext): Selection {
    val field =
        world.schema.field(
            context.nominalType.typeName,
            fieldName,
        )
    val flattenedSubselections =
        when (val resultType = field.typeExpr.baseType) {
            is Schema.SimpleType -> selectionForestOf()

            is Schema.CompositeType ->
                flattenSelectionSet(
                    subselections.orEmpty(),
                    SelectionContext(
                        nominalType = resultType,
                        possibleTypes = resultType.possibleTypes,
                    ),
                )
        }

    return Selection.of(
        key =
            Value.Key.of(
                field = field,
                arguments = arguments,
            ),
        nominalType = context.nominalType,
        possibleTypes = context.possibleTypes,
        subselections = flattenedSubselections,
    )
}

private class SelectionContext(
    val nominalType: Schema.CompositeType,
    val possibleTypes: Set<Schema.ObjectType>,
)
