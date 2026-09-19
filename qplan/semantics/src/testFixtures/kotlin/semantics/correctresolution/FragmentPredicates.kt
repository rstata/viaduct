package semantics.correctresolution

import model.requireQueryTypeDef
import model.Assumptions
import model.ObjectEngineResult
import model.Fragment
import model.merge
import semantics.shared.SharedOperationContext

internal fun ObjectEngineResult.correctResolution(
    operation: SharedOperationContext<*>,
    fragment: Fragment,
): Boolean =
    fragment.nominalType == operation.world.schema.requireQueryTypeDef() &&
        context(operation) {
            correctResolution(
                fragment.subselections
                    .merge(operation.world.schema.requireQueryTypeDef()),
            )
        }

internal fun ObjectEngineResult.rootedAndWellTyped(world: Assumptions, fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.requireQueryTypeDef() &&
        context(world) { this.rootedAndWellTyped() }

internal fun ObjectEngineResult.conformsToFragment(operation: SharedOperationContext<*>, fragment: Fragment): Boolean =
    context(operation) { conformsToSelections(fragment.subselections) }
