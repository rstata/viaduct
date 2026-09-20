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
interface SharedResolverObserver {
    /**
     * Associates a nonempty declared Query fragment with its live root, after orchestration
     * preparation and before dispatch. The root's selected cells and values may be unfinished.
     * This is not an observation of a nested ctx.query call or of completed materialization.
     */
    fun onQueryFragmentPrepared(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    )

    /**
     * Records each attempted resolver call immediately before entering FieldResolver.invoke.
     * Emit inside the execution coroutine, after input preparation, with no suspension,
     * dispatch, or interruptible coroutine-entry boundary between this event and the call.
     */
    fun onResolverInvocation(observation: ResolverInvocationObservation) = Unit

    /**
     * Associates a publication with a reference hop after its helper returns. This does not
     * record a return value or guarantee resolver entry: input errors can short-circuit the helper.
     */
    fun onRootFieldReferenceInvocation(
        observation: RootFieldReferenceInvocationObservation,
    ) = Unit

    companion object {
        fun createNOP(): SharedResolverObserver = NOPResolverObserver
    }
}

/** Read-only invocation identities, Query roots, and reference hops retained by an observer. */
interface ResolverObservations {
    /** Exact attempted invocations; count-sensitive consumers must retain their own event log. */
    fun invokedResolverOccurrences(): Set<ResolverOccurrenceId> = emptySet()

    fun queryFragmentResults(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): List<ObjectEngineResult>

    fun allQueryFragmentResults(): Map<ResolverOccurrenceId, List<ObjectEngineResult>>

    fun rootFieldReferenceInvocations(): List<RootFieldReferenceInvocationObservation> = emptyList()
}

/**
 * Records invocation identities plus Query roots and reference hops. Query and reference records
 * preserve duplicates; invocation identities form a set. Subclasses can retain full invocation
 * events when counts, arguments, or inputs are needed.
 */
open class RecordingResolverObserver : SharedResolverObserver, ResolverObservations {
    private val invokedOccurrences = ConcurrentHashMap.newKeySet<ResolverOccurrenceId>()

    override fun onResolverInvocation(observation: ResolverInvocationObservation) {
        invokedOccurrences += observation.resolverOccurrenceId
    }

    override fun invokedResolverOccurrences(): Set<ResolverOccurrenceId> = invokedOccurrences.toSet()

    private val queryResults =
        ConcurrentHashMap<ResolverOccurrenceId, ConcurrentLinkedQueue<ObjectEngineResult>>()
    private val rootFieldReferenceInvocations =
        ConcurrentLinkedQueue<RootFieldReferenceInvocationObservation>()

    override fun onQueryFragmentPrepared(
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

private object NOPResolverObserver : SharedResolverObserver {
    override fun onQueryFragmentPrepared(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) = Unit
}
