package semantics

import model.ObjectEngineResult
import model.Value

/** Reads one unaliased resolver-visible materialized field by its response key. */
internal fun Value.ObjectFields.getValue(
    key: ObjectEngineResult.GroundKey,
): Value.Output? = getValue(key.field.fieldName)
