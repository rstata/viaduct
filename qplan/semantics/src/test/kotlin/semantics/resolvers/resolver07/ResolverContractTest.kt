package semantics.resolvers.resolver07

import semantics.resolvers.resolver07.resolve

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CompleteObjectFragmentOutputPolicyContract
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.DepthFirstTaskOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentResolverContract
import semantics.contract.QueryFragmentResolverContract
import semantics.contract.ResolverTaskObservation
import semantics.contract.SometimesPassiveObjectFragmentResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.resolvers.resolver06.toContractObservation

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    ObjectFragmentResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    QueryFragmentResolverContract,
    SometimesPassiveResolverContract,
    SometimesPassiveObjectFragmentResolverContract,
    CompleteResolverOutputPolicyContract,
    CompleteObjectFragmentOutputPolicyContract,
    DepthFirstTaskOrderingContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            resolve(selections)
        }

    override fun resolveAndObserveTasks(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): ObjectEngineResult =
        context(world) {
            resolve(
                selections = selections,
                onTaskStarted = { task ->
                    taskObserver(task.toContractObservation())
                },
            )
        }
}
