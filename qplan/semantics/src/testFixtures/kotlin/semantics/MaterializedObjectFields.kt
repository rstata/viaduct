package semantics

import model.ObjectEngineResult
import model.Value
import model.materializedFieldKey

/** Reads one resolver-visible materialized field using its visible ground-key identity. */
internal fun Value.ObjectFields.getValue(
    key: ObjectEngineResult.GroundKey,
): Value.Output? = getValue(key.materializedFieldKey())
