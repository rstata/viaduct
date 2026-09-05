package semantics.resolvers.resolver06

import semantics.resolvers.resolver06.resolve

import viaduct.engine.api.EngineObjectData

import semantics.shared.OperationContext
import model.ObjectEngineResult
import model.SelectionForest
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.DepthFirstTaskOrderingContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.ResolverTaskObservation
import semantics.contract.SometimesPassiveResolverContract
import semantics.contract.UnsupportedParentFieldResolverContract

class ResolverContractTest :
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    UnsupportedParentFieldResolverContract,
    SometimesPassiveResolverContract,
    CompleteResolverOutputPolicyContract,
    DepthFirstTaskOrderingContract,
    CorrectResolutionPostTestPolicy {
    override fun resolve(
        operation: OperationContext,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(operation) {
            resolve(selections)
        }

    override fun resolveAndObserveTasks(
        operation: OperationContext,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): ObjectEngineResult =
        context(operation) {
            resolve(
                selections = selections,
                onTaskStarted = { task ->
                    taskObserver(task.toContractObservation())
                },
            )
        }
}
