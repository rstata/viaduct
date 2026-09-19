package semantics.resolver26

import semantics.resolver26.resolve

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.SelectionForest
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverVariableSingletonCoercionEnabled
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.FromProviderGeneratedResolverContract
import semantics.contract.GeneratedCaseAssertions
import semantics.contract.ListPassiveDeepeningGeneratedResolverContract
import semantics.contract.MixedVariableGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import semantics.contract.ObjectFragmentFromObjectPathGeneratedResolverContract
import semantics.contract.ObjectFragmentGeneratedResolverContract
import semantics.contract.QueryFragmentGeneratedResolverContract
import semantics.contract.RootFieldReferenceGeneratedResolverContract
import semantics.contract.SelectiveNodeGeneratedResolverContract
import semantics.contract.ResolverResolutionObservation
import semantics.contract.SometimesPassiveGeneratedResolverContract
import java.util.concurrent.ConcurrentHashMap
import semantics.shared.SharedOperationContext
import semantics.shared.RecordingResolverObserver

class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    RootFieldReferenceGeneratedResolverContract,
    SelectiveNodeGeneratedResolverContract,
    ListPassiveDeepeningGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    ObjectFragmentFromObjectPathGeneratedResolverContract,
    FromProviderGeneratedResolverContract,
    MixedVariableGeneratedResolverContract,
    QueryFragmentGeneratedResolverContract,
    SometimesPassiveGeneratedResolverContract,
    FeatureInteractionGeneratedResolverContract {
    override val nodeRootFieldReferencesEnabled: Boolean
        get() = true

    override val queryFragmentObjectPathVariablesEnabled: Boolean
        get() = true

    override val queryFragmentQueryPathVariablesEnabled: Boolean
        get() = true

    override val generatedResolverConfigOverrides: Config =
        Config.default +
            (ResolverVariableSingletonCoercionEnabled to true)

    override val selectiveResolvers: Boolean
        get() = true

    override val generatedCaseAssertions =
        GeneratedCaseAssertions.defaultGeneratedContract +
            GeneratedCaseAssertions.exactOrdinaryApplicationCounts +
            GeneratedCaseAssertions.fromFieldBindings

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(operation) {
            resolve(selections)
        }

    override fun observeResolution(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ResolverResolutionObservation {
        val operation =
            SharedOperationContext.create(
                world = world,
                resolverObserver = RecordingResolverObserver(),
            )
        val appliedResolverOccurrences =
            ConcurrentHashMap.newKeySet<ResolverOccurrenceId>()
        val result =
            context(operation) {
                resolveObserved(selections) { application ->
                    appliedResolverOccurrences += application.resolverOccurrenceId
                }
            }
        return Resolver26ResolutionObservation(
            result = result,
            operation = operation,
            appliedResolverOccurrences = appliedResolverOccurrences.toSet(),
        )
    }
}

private data class Resolver26ResolutionObservation(
    override val result: ObjectEngineResult,
    override val operation: SharedOperationContext<*>,
    override val appliedResolverOccurrences: Set<ResolverOccurrenceId>,
) : ResolverResolutionObservation
