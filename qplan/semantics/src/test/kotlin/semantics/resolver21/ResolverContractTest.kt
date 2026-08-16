package semantics.resolver21

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    CompleteResolverOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        context(world) {
            semantics.resolver21.resolve(selections)
        }
}
