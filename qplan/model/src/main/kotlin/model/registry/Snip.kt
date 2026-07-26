package model.registry

import model.Assumptions
import model.Schema
import model.Selection

/**
 * Projects this object to the fields selected by [selections].
 *
 * Every selection must be declared on this object's type or one of its nominal supertypes. All
 * selections at every depth must select fields that take no arguments. A type-conditioned
 * selection that does not apply to this concrete object type is omitted.
 *
 * @throws IllegalArgumentException when either precondition is not met
 */
context(world: Assumptions)
fun Schema.ObjectValue.snip(selections: List<Selection>): Schema.ObjectValue {
    requireArgumentless(selections)
    selections.forEach { selection ->
        val relation =
            world.schema.relation(
                selection.nominalType.typeName,
                type.typeName,
            )
        require(
            relation == Schema.TypeRelation.SAME ||
                relation == Schema.TypeRelation.WIDER_THAN,
        ) {
            "${selection.nominalType.typeName} is not a supertype of ${type.typeName}"
        }
    }

    val selectedFields =
        selections
            .filter { type in it.possibleTypes }
            .groupBy { it.key.field.fieldName }
            .mapValues { (fieldName, fieldSelections) ->
                val value = outputObjectFields.getValue(fieldName)
                val firstSelection = fieldSelections.first()
                if (firstSelection.isLeaf) {
                    value
                } else {
                    val subselections = fieldSelections.flatMap { it.subselections }
                    value.snipOutput(subselections)
                }
            }

    return world.schema.objectValue(type, selectedFields)
}

private fun requireArgumentless(selections: List<Selection>) {
    selections.forEach { selection ->
        require(selection.key.field.arguments == Schema.NoArguments) {
            "Cannot snip argument-taking field " +
                "${selection.nominalType.typeName}.${selection.key.field.fieldName}"
        }
        requireArgumentless(selection.subselections)
    }
}

context(world: Assumptions)
private fun Schema.OutputValue?.snipOutput(
    selections: List<Selection>,
): Schema.OutputValue? =
    when (this) {
        null,
        Schema.ErrorValue,
        -> this

        is Schema.ObjectValue -> snip(selections)
        is Schema.ListValue ->
            world.schema.outputListValue(
                outputListValues.map { it.snipOutput(selections) },
            )

        is Schema.SimpleValue ->
            throw IllegalArgumentException(
                "Cannot apply subselections to simple value of type $typeName",
            )
    }
