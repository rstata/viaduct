package semantics.resolver26

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.Value
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.LateObjectPathDemandResolverContract
import semantics.contract.LateAncestorDemandPolicy
import semantics.contract.NodeResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentFromObjectPathResolverContract
import semantics.contract.ObjectFragmentResolverContract
import semantics.contract.SelectiveObjectFragmentOutputPolicyContract
import semantics.contract.SelectiveResolverOutputPolicyContract
import semantics.contract.VariableSelectionIdentityPolicy
import semantics.contract.VariableSelectionIdentityResolverContract

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    ObjectFragmentResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    ObjectFragmentFromObjectPathResolverContract,
    VariableSelectionIdentityResolverContract,
    LateObjectPathDemandResolverContract,
    SelectiveResolverOutputPolicyContract,
    SelectiveObjectFragmentOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override val variableSelectionIdentityPolicy: VariableSelectionIdentityPolicy
        get() = VariableSelectionIdentityPolicy.PRESERVE_RESPONSE_GROUP_OCCURRENCES

    override val lateAncestorDemandPolicy: LateAncestorDemandPolicy
        get() = LateAncestorDemandPolicy.CONTRIBUTE_PASSIVE_PREDECESSORS

    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            semantics.resolver26.resolve(selections)
        }
}
