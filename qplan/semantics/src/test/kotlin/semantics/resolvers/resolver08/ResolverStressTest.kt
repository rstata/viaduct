package semantics.resolvers.resolver08

import semantics.resolvers.resolver08.resolve

import viaduct.engine.api.EngineObjectData

import semantics.shared.SharedOperationContext
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.DeepResolverStressContract

class ResolverStressTest : DeepResolverStressContract {
    override val resolverName: String = "resolver08"

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(operation) {
            resolve(selections)
        }
}
