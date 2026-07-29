package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Fragment

/**
 * Whether this result and [fragment] are rooted at the reasoning world's canonical Query type.
 *
 * The [EngineResult.Object] receiver already establishes that the result is object-valued.
 */
context(world: Assumptions)
fun EngineResult.Object.rootedAndWellTyped(fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.query && this.type == world.schema.query
