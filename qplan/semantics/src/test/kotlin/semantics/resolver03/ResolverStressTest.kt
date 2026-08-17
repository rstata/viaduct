package semantics.resolver03

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.Value
import semantics.contract.DeepResolverStressContract

class ResolverStressTest : DeepResolverStressContract {
    override val resolverName: String = "resolver03"

    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            semantics.resolver03.resolve(selections)
        }
}
