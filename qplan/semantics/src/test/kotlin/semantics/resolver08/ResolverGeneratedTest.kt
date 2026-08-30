package semantics.resolver08

import semantics.resolver08.resolve

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.ListPassiveDeepeningGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import semantics.contract.ObjectFragmentGeneratedResolverContract
import semantics.contract.QueryFragmentGeneratedResolverContract

class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    ListPassiveDeepeningGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    QueryFragmentGeneratedResolverContract,
    FeatureInteractionGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = true

    override fun resolve(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            resolve(selections)
        }
}
