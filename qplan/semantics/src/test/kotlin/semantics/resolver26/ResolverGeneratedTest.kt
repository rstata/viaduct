package semantics.resolver26

import semantics.resolver26.resolve

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.SelectionForest
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverVariableSingletonCoercionEnabled
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.GeneratedCaseAssertions
import semantics.contract.ListPassiveDeepeningGeneratedResolverContract
import semantics.contract.MixedVariableGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import semantics.contract.ObjectFragmentFromObjectPathGeneratedResolverContract
import semantics.contract.ObjectFragmentGeneratedResolverContract
import semantics.contract.QueryFragmentGeneratedResolverContract
import semantics.contract.ResolverResolutionObservation
import semantics.contract.SometimesPassiveGeneratedResolverContract
import java.util.concurrent.ConcurrentHashMap

class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    ListPassiveDeepeningGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    ObjectFragmentFromObjectPathGeneratedResolverContract,
    MixedVariableGeneratedResolverContract,
    QueryFragmentGeneratedResolverContract,
    SometimesPassiveGeneratedResolverContract,
    FeatureInteractionGeneratedResolverContract {
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
            GeneratedCaseAssertions.objectPathBindings

    override fun resolve(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult =
        context(world) {
            resolve(selections)
        }

    override fun observeResolution(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ResolverResolutionObservation {
        val appliedResolverOccurrences =
            ConcurrentHashMap.newKeySet<ResolverOccurrenceId>()
        val result =
            context(world) {
                resolveObserved(selections) { application ->
                    appliedResolverOccurrences += application.resolverOccurrenceId
                }
            }
        return Resolver26ResolutionObservation(
            result = result,
            appliedResolverOccurrences = appliedResolverOccurrences.toSet(),
        )
    }
}

private data class Resolver26ResolutionObservation(
    override val result: ObjectEngineResult,
    override val appliedResolverOccurrences: Set<ResolverOccurrenceId>,
) : ResolverResolutionObservation
