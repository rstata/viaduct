package semantics.correctresolution

import model.requireQueryTypeDef
import model.Assumptions
import model.ObjectEngineResult
import model.Fragment
import model.merge
import semantics.shared.OperationContext

context(operation: OperationContext)
internal fun ObjectEngineResult.correctResolution(
    fragment: Fragment,
): Boolean =
    fragment.nominalType == operation.schema.requireQueryTypeDef() &&
        correctResolution(
            fragment.subselections
                .merge(operation.schema.requireQueryTypeDef()),
        )

context(world: Assumptions)
internal fun ObjectEngineResult.rootedAndWellTyped(fragment: Fragment): Boolean =
    fragment.nominalType == world.schema.requireQueryTypeDef() && this.rootedAndWellTyped()

context(operation: OperationContext)
internal fun ObjectEngineResult.conformsToFragment(fragment: Fragment): Boolean =
    conformsToSelections(fragment.subselections)
