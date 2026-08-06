package semantics.resolver04

import model.Assumptions
import model.Selection
import model.SelectionForest
import model.Value
import model.objectKey
import model.selectionForestOf

/**
 * Returns the largest recursively available portion of [demand] in this output.
 *
 * Null and error outputs admit every selection because projection preserves them without reading a
 * field. Behavioral selections are also admitted because projection stops at their boundary.
 * Passive selections are retained only when every applicable concrete object contains their exact
 * key; their descendants are filtered by the corresponding values.
 */
context(world: Assumptions)
internal fun Value.Output?.availableDemand(demand: SelectionForest): SelectionForest =
    demand.flatMap { selection ->
        availableSelection(listOf(this), selection)
            ?.let { available -> selectionForestOf(available) }
            ?: selectionForestOf()
    }

context(world: Assumptions)
private fun availableSelection(
    outputs: List<Value.Output?>,
    selection: Selection,
): Selection? {
    val values =
        outputs.flatMap { output ->
            when (output) {
                is Value.OutputList -> output.values
                else -> listOf(output)
            }
        }
    val applicableObjects =
        values
            .filter { value -> value != Value.Error }
            .filterIsInstance<Value.Object>()
            .filter { value -> value.type in selection.possibleTypes }
    val passiveObjects =
        applicableObjects.filter { value ->
            !world.behavioral(
                value.type.fields.getValue(selection.key.field.fieldName),
            )
        }
    val passiveValues =
        passiveObjects.map { value ->
            val key = selection.objectKey(value.type)
            if (key !in value.fieldValues) return null
            value.fieldValues.getValue(key)
        }
    val availableSubselections =
        selection.subselections.flatMap { subselection ->
            availableSelection(passiveValues, subselection)
                ?.let { available -> selectionForestOf(available) }
                ?: selectionForestOf()
        }
    return Selection.of(
        key = selection.key,
        possibleTypes = selection.possibleTypes,
        subselections = availableSubselections,
    )
}
