package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema

/**
 * Whether every present `__typename` cell names its containing object's concrete type.
 *
 * This predicate does not require a `__typename` cell to be present. It observes cell values but
 * never cell check components.
 */
context(world: Assumptions)
fun ObjectEngineResult.conformsToTypename(): Boolean =
    objectConformsToTypename()

context(world: Assumptions)
private fun ObjectEngineResult.objectConformsToTypename(): Boolean =
    keys.all { key ->
        val value = fetch(key).value
        if (key.field.fieldName == "__typename") {
            value != Schema.ErrorValue &&
                value is Schema.StringValue &&
                value.stringValue == type.typeName
        } else {
            value.engineResultConformsToTypename()
        }
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToTypename(): Boolean =
    when (this) {
        null,
        Schema.ErrorValue,
        is Schema.SimpleValue,
        -> true

        is ObjectEngineResult -> objectConformsToTypename()
        is ListEngineResult -> all { cell -> cell.value.engineResultConformsToTypename() }
    }
