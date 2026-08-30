package semantics.resolver25

import semantics.resolver25.resolve

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
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
import semantics.contract.SometimesPassiveObjectFragmentResolverContract
import semantics.contract.SometimesPassiveObjectPathResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.contract.SometimesPassiveSelectiveResolverContract
import semantics.contract.VariableSelectionIdentityPolicy
import semantics.contract.VariableSelectionIdentityResolverContract

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    ObjectFragmentResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    ObjectFragmentFromObjectPathResolverContract,
    SometimesPassiveResolverContract,
    SometimesPassiveObjectFragmentResolverContract,
    SometimesPassiveObjectPathResolverContract,
    SometimesPassiveSelectiveResolverContract,
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
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            resolve(selections)
        }
}
