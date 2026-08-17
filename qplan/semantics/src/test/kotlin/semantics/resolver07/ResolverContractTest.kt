package semantics.resolver07

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.Value
import semantics.contract.CompleteObjectFragmentOutputPolicyContract
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.DepthFirstTaskOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentResolverContract
import semantics.contract.ResolverTaskObservation
import semantics.toContractObservation

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    ObjectFragmentResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    CompleteResolverOutputPolicyContract,
    CompleteObjectFragmentOutputPolicyContract,
    DepthFirstTaskOrderingContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            semantics.resolver07.resolve(selections)
        }

    override fun resolveAndObserveTasks(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): ObjectEngineResult =
        context(world) {
            semantics.resolver07.resolve(
                selections = selections,
                eventObserver = { event ->
                    event.toContractObservation()?.let(taskObserver)
                },
            )
        }
}
