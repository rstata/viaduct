package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Schema
import model.Value

/**
 * Whether every present `__typename` cell names its containing object's concrete type.
 *
 * This predicate does not require a `__typename` cell to be present. It observes cell values but
 * never cell check components.
 */
context(world: Assumptions)
fun EngineResult.Object.conformsToTypename(): Boolean =
    objectConformsToTypename()

context(world: Assumptions)
private fun EngineResult.Object.objectConformsToTypename(): Boolean =
    keys.all { key ->
        val value = fetch(key).value
        if (key.field.fieldName == "__typename") {
            value != Value.Error &&
                value is Value.String &&
                value.stringValue == type.typeName
        } else {
            value.engineResultConformsToTypename()
        }
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToTypename(): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object -> objectConformsToTypename()
        is EngineResult.List -> all { cell -> cell.value.engineResultConformsToTypename() }
    }
