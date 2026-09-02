package semantics.resolver26

import semantics.resolver26.resolve

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverVariableSingletonCoercionEnabled
import semantics.arbitrary.SometimesPassiveFieldWeight
import semantics.contract.DeepResolverStressContract

class ResolverStressTest : DeepResolverStressContract {
    override val resolverName: String = "resolver26"

    override val objectPathVariablesEnabled: Boolean = true

    override val sometimesPassiveCoverageRequired: Boolean = true

    override val stressConfigOverrides: Config =
        Config.default +
            (ResolverVariableSingletonCoercionEnabled to true) +
            (SometimesPassiveFieldWeight to 0.25)

    override fun resolve(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            resolve(selections)
        }
}
