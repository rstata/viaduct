package semantics.resolver25

import model.Assumptions
import model.EngineResult
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
        get() = VariableSelectionIdentityPolicy.MERGE_EQUAL_GROUNDED_KEYS

    override val lateAncestorDemandPolicy: LateAncestorDemandPolicy
        get() = LateAncestorDemandPolicy.RETAIN_OPEN_VARIABLE_BOUNDARY

    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        context(world) {
            semantics.resolver25.resolve(selections)
        }
}
