package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.Fragment
import model.instantiateBindings
import model.merge

context(world: Assumptions)
internal fun ObjectEngineResult.correctResolution(fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.query &&
        correctResolution(
            fragment.subselections
                .merge(world.schema.query)
                .instantiateBindings(),
        )

context(world: Assumptions)
internal fun ObjectEngineResult.rootedAndWellTyped(fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.query && rootedAndWellTyped()

context(world: Assumptions)
internal fun ObjectEngineResult.conformsToFragment(fragment: Fragment): Boolean =
    conformsToSelections(fragment.subselections)
