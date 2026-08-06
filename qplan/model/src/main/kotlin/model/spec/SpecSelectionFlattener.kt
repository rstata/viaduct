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
 * type conditions to the fields they contain. At each selection-set level, every source field
 * occurrence contributes one member to the corresponding [SelectionForest], which erases source
 * order while preserving occurrences and does not define semantic equality for [Selection]. This
 * one-to-one correspondence is a postcondition of this translation; later normalization with
 * [model.merge] may combine members that have equal concrete structural keys.
 */
context(world: Assumptions)
fun flatten(
    typeInScope: Schema.CompositeType,
    selectionSet: List<SpecSelection>,
): SelectionForest =
    flatten(
        schema = world.schema,
        typeInScope = typeInScope,
        selectionSet = selectionSet,
    )

internal fun flatten(
    schema: Schema,
    typeInScope: Schema.CompositeType,
    selectionSet: List<SpecSelection>,
): SelectionForest {
    val initialContext =
        SelectionContext(
            nominalType = typeInScope,
            possibleTypes = typeInScope.possibleTypes,
        )
    return flattenSelectionSet(schema, selectionSet, initialContext)
}

private fun flattenSelectionSet(
    schema: Schema,
    selections: List<SpecSelection>,
    context: SelectionContext,
): SelectionForest =
    selections.fold(selectionForestOf()) { result, selection ->
        result +
            when (selection) {
                is SpecSelection.Field ->
                    selectionForestOf(selection.flattenField(schema, context))
                is SpecSelection.InlineFragment -> {
                    val fragmentContext =
                        selection.typeCondition?.let { typeCondition ->
                            SelectionContext(
                                nominalType = typeCondition,
                                possibleTypes =
                                    context.possibleTypes intersect typeCondition.possibleTypes,
                            )
                        } ?: context
                    flattenSelectionSet(schema, selection.selections, fragmentContext)
                }
            }
    }

private fun SpecSelection.Field.flattenField(
    schema: Schema,
    context: SelectionContext,
): Selection {
    val field =
        schema.field(
            context.nominalType.typeName,
            fieldName,
        )
    val flattenedSubselections =
        when (val resultType = field.typeExpr.baseType) {
            is Schema.SimpleType -> selectionForestOf()

            is Schema.CompositeType ->
                flattenSelectionSet(
                    schema,
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
        possibleTypes = context.possibleTypes,
        subselections = flattenedSubselections,
    )
}

private class SelectionContext(
    val nominalType: Schema.CompositeType,
    val possibleTypes: Set<Schema.ObjectType>,
)
