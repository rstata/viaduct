package semantics.resolver24

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.resolver10.DemandSealingContract

class DemandSealingTest : DemandSealingContract {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        resolveWithBindingValidation(world, root, selections)
}
