package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.SimpleEngineResult
import model.StringEngineResult

/**
 * Whether every retained `__typename` value names its containing object's concrete type.
 *
 * Resolver behavior recursively supplies this ordinary passive field, and the registry supplies it
 * on the root Query object. Selective resolution may omit it when undemanded; complete-output
 * resolution retains it with the other passive fields. This predicate observes retained values but
 * never access-acceptance results.
 */
context(world: Assumptions)
fun ObjectEngineResult.conformsToTypename(): Boolean =
    objectConformsToTypename()

context(world: Assumptions)
private fun ObjectEngineResult.objectConformsToTypename(): Boolean =
    keys.all { key ->
        val value = getCell(key).getValue().get()
        if (key.field.fieldName == "__typename") {
            value != ErrorEngineResult &&
                value is StringEngineResult &&
                value.stringValue == type.typeName
        } else {
            value.engineResultConformsToTypename()
        }
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToTypename(): Boolean =
    when (this) {
        null,
        ErrorEngineResult,
        is SimpleEngineResult,
        -> true

        is ObjectEngineResult -> objectConformsToTypename()
        is ListEngineResult ->
            all { cell -> cell.getValue().get().engineResultConformsToTypename() }
    }
