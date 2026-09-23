package semantics.resolvers.resolver01

import semantics.resolvers.resolver01.resolve

import viaduct.engine.api.EngineObjectData

import semantics.shared.SharedOperationContext
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.FrozenObjectResolutionContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.CompleteOutputRootFieldReferenceResolverContract
import semantics.contract.DepthFirstRootFieldReferenceOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.RootFieldReferenceResolverContract
import semantics.contract.SometimesPassiveResolverContract

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    FrozenObjectResolutionContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    CompleteOutputRootFieldReferenceResolverContract,
    DepthFirstRootFieldReferenceOrderingContract,
    SometimesPassiveResolverContract,
    CompleteResolverOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        operation.resolve(selections)
}
