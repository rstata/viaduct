package semantics.resolver06

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.DepthFirstTaskOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.ResolverTaskObservation
import semantics.toContractObservation

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    CompleteResolverOutputPolicyContract,
    DepthFirstTaskOrderingContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        context(world) {
            root.resolve(selections)
        }

    override fun resolveAndObserveTasks(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): EngineResult.Object =
        context(world) {
            root.resolve(
                selections = selections,
                taskObserver = { task -> taskObserver(task.toContractObservation()) },
            )
        }
}
