package semantics.resolver26

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.Value
import semantics.contract.ResolverWitnessContract

class ResolverWitnessTest : ResolverWitnessContract {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            semantics.resolver26.resolve(selections)
        }
}
