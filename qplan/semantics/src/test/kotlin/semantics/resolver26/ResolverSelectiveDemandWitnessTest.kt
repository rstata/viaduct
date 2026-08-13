package semantics.resolver26

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.ResolverSelectiveDemandWitnessContract

class ResolverSelectiveDemandWitnessTest : ResolverSelectiveDemandWitnessContract {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        context(world) {
            root.resolve(selections)
        }
}
