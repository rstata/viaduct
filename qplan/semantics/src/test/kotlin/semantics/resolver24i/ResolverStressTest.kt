package semantics.resolver24i

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.DeepResolverStressContract

class ResolverStressTest : DeepResolverStressContract {
    override val resolverName: String = "resolver24i"
    override val objectPathVariablesEnabled: Boolean = true
    override val nodeResolversEnabled: Boolean = false
    override val mixedVariableCoverageRequired: Boolean = true

    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        resolveSubject(world, root, selections)
}
