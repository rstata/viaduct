package semantics.resolver25

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.ResolverWitnessContract

class ResolverWitnessTest : ResolverWitnessContract {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        resolveWithLifecycleValidation(world, root, selections)
}
