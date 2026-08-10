package semantics.resolver24

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.DeepResolverStressContract

class ResolverStressTest : DeepResolverStressContract {
    override val resolverName: String = "resolver24"
    override val objectPathVariablesEnabled: Boolean = true

    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        resolveWithBindingValidation(world, root, selections)
}
