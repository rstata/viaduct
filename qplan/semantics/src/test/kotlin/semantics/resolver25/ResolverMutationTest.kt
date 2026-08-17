package semantics.resolver25

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.Value
import semantics.contract.ResolverMutationContract

class ResolverMutationTest : ResolverMutationContract {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): ObjectEngineResult =
        resolveWithLifecycleValidation(world, root, selections)
}
