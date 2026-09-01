package semantics.resolver21

import semantics.resolver21.resolve

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.SometimesPassiveGeneratedResolverContract

class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    SometimesPassiveGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = false

    override fun resolve(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            resolve(selections)
        }
}
