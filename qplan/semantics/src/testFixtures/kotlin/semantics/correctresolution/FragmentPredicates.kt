package semantics.correctresolution

import model.requireQueryTypeDef
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.Fragment
import model.instantiateBindings
import model.merge

context(world: Assumptions)
internal fun ObjectEngineResult.correctResolution(fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.requireQueryTypeDef() &&
        correctResolution(
            fragment.subselections
                .merge(world.schema.requireQueryTypeDef())
                .instantiateBindings(),
        )

context(world: Assumptions)
internal fun ObjectEngineResult.rootedAndWellTyped(fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.requireQueryTypeDef() && rootedAndWellTyped()

context(world: Assumptions)
internal fun ObjectEngineResult.conformsToFragment(fragment: Fragment): Boolean =
    conformsToSelections(fragment.subselections)
