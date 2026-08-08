package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.mergeToGround

context(world: Assumptions)
internal fun EngineResult.Object.correctResolution(fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.query &&
        correctResolution(fragment.subselections.mergeToGround(world.schema.query))

context(world: Assumptions)
internal fun EngineResult.Object.rootedAndWellTyped(fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.query && rootedAndWellTyped()

context(world: Assumptions)
internal fun EngineResult.Object.conformsToFragment(fragment: Fragment): Boolean =
    conformsToSelections(fragment.subselections)
