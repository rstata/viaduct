package semantics.contract

import model.EngineResult

/** Returns the completed value slot for concise result-shape assertions. */
internal fun EngineResult.Cell.get(): EngineResult? = getValue().get()
