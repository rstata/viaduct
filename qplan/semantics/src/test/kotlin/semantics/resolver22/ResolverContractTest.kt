package semantics.resolver22

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.CompleteObjectFragmentOutputPolicyContract
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentResolverContract

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    ObjectFragmentResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    CompleteResolverOutputPolicyContract,
    CompleteObjectFragmentOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        context(world) {
            root.resolve(selections)
        }
}
