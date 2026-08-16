package semantics.resolver26

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.ResolverMutationContract

class ResolverMutationTest : ResolverMutationContract {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        context(world) {
            semantics.resolver26.resolve(selections)
        }
}
