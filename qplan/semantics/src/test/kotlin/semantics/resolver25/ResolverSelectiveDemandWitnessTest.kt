package semantics.resolver25

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.ResolverSelectiveDemandWitnessContract

class ResolverSelectiveDemandWitnessTest : ResolverSelectiveDemandWitnessContract {
    override fun resolve(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        resolveWithLifecycleValidation(world, root, selections)
}
