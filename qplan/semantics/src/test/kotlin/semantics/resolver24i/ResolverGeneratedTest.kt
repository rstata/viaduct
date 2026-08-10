package semantics.resolver24i

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.MixedVariableGeneratedResolverContract

class ResolverGeneratedTest : MixedVariableGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = true

    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        resolveSubject(world, root, selections)
}
