package semantics.resolvers.resolver21

import semantics.resolvers.resolver21.resolve

import viaduct.engine.api.EngineObjectData

import semantics.shared.SharedOperationContext
import model.ObjectEngineResult
import model.ErrorEngineResult
import model.SelectionForest
import semantics.contract.CompleteResolverOutputPolicyContract
import semantics.contract.CompleteOutputRootFieldReferenceResolverContract
import semantics.contract.CorrectResolutionPostTestPolicy
import semantics.contract.FrozenObjectResolutionContract
import semantics.contract.EmptyObjectFragmentResolverContract
import semantics.contract.NodeResolverContract
import semantics.contract.RootFieldReferenceResolverContract
import semantics.contract.SometimesPassiveResolverContract
import semantics.contract.UnsupportedParentFieldResolverContract
import kotlin.test.assertIs

class ResolverContractTest :
    FrozenObjectResolutionContract,
    EmptyObjectFragmentResolverContract,
    NodeResolverContract,
    RootFieldReferenceResolverContract,
    CompleteOutputRootFieldReferenceResolverContract,
    UnsupportedParentFieldResolverContract,
    SometimesPassiveResolverContract,
    CompleteResolverOutputPolicyContract,
    CorrectResolutionPostTestPolicy {
    override fun unsupportedParentFailure(resolve: () -> ObjectEngineResult): IllegalArgumentException {
        val result = resolve()
        val error = assertIs<ErrorEngineResult>(result.getCell(result.keys.single()).getValue().get())
        return assertIs<IllegalArgumentException>(error.errorData.cause)
    }

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        operation.resolve(selections)
}
