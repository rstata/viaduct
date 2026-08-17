package semantics.resolver23

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.Value
import semantics.contract.DeepResolverStressContract

class ResolverStressTest : DeepResolverStressContract {
    override val resolverName: String = "resolver23"

    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            semantics.resolver23.resolve(selections)
        }
}
