package semantics.shared

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import model.ObjectEngineResult.ObjectKey
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.SelectionForest

/** Evidence connecting one symbolic reference hop to its independently rooted invocation. */
data class RootFieldReferenceInvocationObservation(
    val publicationRoot: ObjectEngineResult,
    val publicationPath: List<PathComponent>,
    val reference: RootFieldReferenceData,
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val invocationKey: ObjectKey,
    val suppliedDemand: SelectionForest,
)

/**
 * Receives semantically passive observations from one resolver operation.
 *
 * Replacing a normally returning, non-mutating observer with [createNOP] preserves semantic
 * resolution results. Because callbacks are synchronous, an observer may still affect failure or
 * latency by throwing or blocking.
 */
interface ResolverObserver {
    fun onQueryFragmentResult(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    )

    fun onRootFieldReferenceInvocation(
        observation: RootFieldReferenceInvocationObservation,
    ) = Unit

    companion object {
        fun createNOP(): ResolverObserver = NOPResolverObserver
    }
}

/** Read-only Query-fragment evidence retained by an instrumented resolver observer. */
interface ResolverObservations {
    fun queryFragmentResults(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): List<ObjectEngineResult>

    fun allQueryFragmentResults(): Map<ResolverOccurrenceId, List<ObjectEngineResult>>

    fun rootFieldReferenceInvocations(): List<RootFieldReferenceInvocationObservation> = emptyList()
}

/** Records every observation without rejecting or overwriting duplicates. */
class RecordingResolverObserver : ResolverObserver, ResolverObservations {
    private val queryResults =
        ConcurrentHashMap<ResolverOccurrenceId, ConcurrentLinkedQueue<ObjectEngineResult>>()
    private val rootFieldReferenceInvocations =
        ConcurrentLinkedQueue<RootFieldReferenceInvocationObservation>()

    override fun onQueryFragmentResult(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) {
        queryResults
            .computeIfAbsent(resolverOccurrenceId) { ConcurrentLinkedQueue() }
            .add(result)
    }

    override fun queryFragmentResults(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): List<ObjectEngineResult> = queryResults[resolverOccurrenceId]?.toList().orEmpty()

    override fun allQueryFragmentResults(): Map<ResolverOccurrenceId, List<ObjectEngineResult>> =
        queryResults.mapValues { (_, results) -> results.toList() }

    override fun onRootFieldReferenceInvocation(
        observation: RootFieldReferenceInvocationObservation,
    ) {
        rootFieldReferenceInvocations.add(observation)
    }

    override fun rootFieldReferenceInvocations(): List<RootFieldReferenceInvocationObservation> =
        rootFieldReferenceInvocations.toList()
}

private object NOPResolverObserver : ResolverObserver {
    override fun onQueryFragmentResult(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) = Unit
}
