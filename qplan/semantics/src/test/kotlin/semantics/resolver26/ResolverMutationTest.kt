package semantics.resolver26

import semantics.resolver26.resolve

import viaduct.engine.api.EngineObjectData

import semantics.shared.SharedOperationContext
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.ResolverMutationContract

class ResolverMutationTest : ResolverMutationContract {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(operation) {
            resolve(selections)
        }
}
