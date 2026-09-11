package semantics.resolvers.resolver02

import semantics.resolvers.resolver02.resolve

import viaduct.engine.api.EngineObjectData

import semantics.shared.OperationContext
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CompleteObjectFragmentOutputPolicyContract
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CompleteOutputRootFieldReferenceResolverContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.DepthFirstRootFieldReferenceOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentResolverContract
import semantics.contract.QueryFragmentResolverContract
import semantics.contract.QueryFragmentRootFieldReferenceResolverContract
import semantics.contract.RootFieldReferenceResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.contract.SometimesPassiveObjectFragmentResolverContract
import semantics.contract.UnsupportedParentFieldResolverContract

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    CompleteOutputRootFieldReferenceResolverContract,
    DepthFirstRootFieldReferenceOrderingContract,
    QueryFragmentRootFieldReferenceResolverContract,
    ObjectFragmentResolverContract,
    UnsupportedParentFieldResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    QueryFragmentResolverContract,
    SometimesPassiveResolverContract,
    SometimesPassiveObjectFragmentResolverContract,
    CompleteResolverOutputPolicyContract,
    CompleteObjectFragmentOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        operation: OperationContext,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(operation) {
            resolve(selections)
        }
}
