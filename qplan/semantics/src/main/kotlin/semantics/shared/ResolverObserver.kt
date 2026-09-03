package semantics.shared

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import model.ObjectEngineResult
import model.ResolverOccurrenceId

/** Receives semantically passive observations from one resolver operation. */
interface ResolverObserver {
    fun onQueryFragmentResult(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    )

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
}

/** Records every observation without rejecting or overwriting duplicates. */
class RecordingResolverObserver : ResolverObserver, ResolverObservations {
    private val queryResults =
        ConcurrentHashMap<ResolverOccurrenceId, ConcurrentLinkedQueue<ObjectEngineResult>>()

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
}

private object NOPResolverObserver : ResolverObserver {
    override fun onQueryFragmentResult(
        resolverOccurrenceId: ResolverOccurrenceId,
        result: ObjectEngineResult,
    ) = Unit
}
