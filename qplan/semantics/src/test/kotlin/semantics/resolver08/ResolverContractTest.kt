package semantics.resolver08

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.DepthFirstTaskOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.ObjectFragmentFromArgumentResolverContract
import semantics.contract.ObjectFragmentResolverContract
import semantics.contract.ResolverTaskObservation
import semantics.contract.SelectiveObjectFragmentOutputPolicyContract
import semantics.contract.SelectiveResolverOutputPolicyContract
import semantics.toContractObservation

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    ObjectFragmentResolverContract,
    ObjectFragmentFromArgumentResolverContract,
    SelectiveResolverOutputPolicyContract,
    SelectiveObjectFragmentOutputPolicyContract,
    DepthFirstTaskOrderingContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        context(world) {
            semantics.resolver08.resolve(selections)
        }

    override fun resolveAndObserveTasks(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): EngineResult.Object =
        context(world) {
            semantics.resolver08.resolve(
                selections = selections,
                eventObserver = { event ->
                    event.toContractObservation()?.let(taskObserver)
                },
            )
        }
}
