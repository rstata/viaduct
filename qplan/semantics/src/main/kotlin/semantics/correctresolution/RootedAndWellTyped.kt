package semantics.correctresolution

import model.Assumptions
import model.EngineResult

/**
 * Whether this result is rooted at the reasoning world's canonical Query type.
 *
 * The [EngineResult.Object] receiver already establishes that the result is object-valued.
 */
context(world: Assumptions)
fun EngineResult.Object.rootedAndWellTyped(): Boolean =
    type == world.schema.query
