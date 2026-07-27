package semantics.correctresolution

import model.Assumptions
import model.Fragment
import model.ObjectEngineResult

/**
 * Whether this result and [fragment] are rooted at the reasoning world's canonical Query type.
 *
 * The [ObjectEngineResult] receiver already establishes that the result is object-valued.
 */
context(world: Assumptions)
fun ObjectEngineResult.rootedAndWellTyped(fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.query && this.type == world.schema.query
