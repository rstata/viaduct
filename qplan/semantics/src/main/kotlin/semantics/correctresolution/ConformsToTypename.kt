package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Schema
import model.Value

/**
 * Whether every present `__typename` value names its containing object's concrete type.
 *
 * This predicate does not require a `__typename` value to be present. It observes values but never
 * field or type checks.
 */
context(world: Assumptions)
fun EngineResult.Object.conformsToTypename(): Boolean =
    objectConformsToTypename()

context(world: Assumptions)
private fun EngineResult.Object.objectConformsToTypename(): Boolean =
    keys.all { key ->
        val value = getValue(key).get()
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
        is EngineResult.List -> all { value -> value.engineResultConformsToTypename() }
    }
