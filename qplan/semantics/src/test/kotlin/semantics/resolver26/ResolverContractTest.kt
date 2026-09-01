package semantics.resolver26

import semantics.resolver26.resolve

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
import semantics.contract.ProductionDeadlockResolverContract
import semantics.contract.QueryFragmentResolverContract
import semantics.contract.QueryFragmentFromObjectPathResolverContract
import semantics.contract.SelectiveObjectFragmentOutputPolicyContract
import semantics.contract.SelectiveResolverOutputPolicyContract
import semantics.contract.SometimesPassiveObjectFragmentResolverContract
import semantics.contract.SometimesPassiveObjectPathResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.contract.SometimesPassiveSelectiveResolverContract
import semantics.contract.VariableSelectionIdentityResolverContract

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    ObjectFragmentResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    ObjectFragmentFromObjectPathResolverContract,
    QueryFragmentResolverContract,
    QueryFragmentFromObjectPathResolverContract,
    SometimesPassiveResolverContract,
    SometimesPassiveObjectFragmentResolverContract,
    SometimesPassiveObjectPathResolverContract,
    SometimesPassiveSelectiveResolverContract,
    ProductionDeadlockResolverContract,
    VariableSelectionIdentityResolverContract,
    LateObjectPathDemandResolverContract,
    SelectiveResolverOutputPolicyContract,
    SelectiveObjectFragmentOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override val lateAncestorDemandPolicy: LateAncestorDemandPolicy
        get() = LateAncestorDemandPolicy.CONTRIBUTE_PASSIVE_PREDECESSORS

    override val retainsSymbolicObjectKeys: Boolean
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
