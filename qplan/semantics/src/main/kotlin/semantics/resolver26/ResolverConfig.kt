package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.Arguments

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import model.PathComponent
import model.ResolverOccurrenceId
import model.SelectionForest
import model.MaterializeSelectionForest
import viaduct.engine.api.EngineObjectData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import model.ObjectEngineResult
import semantics.shared.ResolverObserver

internal const val RESOLVER26_THREAD_COUNT_PROPERTY = "resolver26.thread.count"
internal const val RESOLVER26_THREAD_COUNT_ENVIRONMENT = "RESOLVER26_THREAD_COUNT"

internal data class Resolver26ApplicationObservation(
    val occurrencePath: List<PathComponent>,
    val field: ViaductSchema.ObjectField,
    val input: EngineObjectData.Sync,
    val inputSelections: MaterializeSelectionForest,
    val arguments: Arguments.Resolved,
    val suppliedDemand: SelectionForest,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val variableArgumentCount: Int,
    val variableResolverOccurrenceIds: Set<ResolverOccurrenceId>,
)

/** Semantically passive Resolver26 application instrumentation. */
internal typealias Resolver26ApplicationObserver = (Resolver26ApplicationObservation) -> Unit

/** Resolver26's semantically passive extension of the shared observation boundary. */
internal interface Resolver26Observer : ResolverObserver {
    fun onResolverApplication(observation: Resolver26ApplicationObservation)
}

internal fun ResolverObserver.withResolver26Applications(
    applicationObserver: Resolver26ApplicationObserver,
): Resolver26Observer {
    val delegate = this
    return object : Resolver26Observer {
        override fun onQueryFragmentResult(
            resolverOccurrenceId: ResolverOccurrenceId,
            result: ObjectEngineResult,
        ) = delegate.onQueryFragmentResult(resolverOccurrenceId, result)

        override fun onResolverApplication(observation: Resolver26ApplicationObservation) {
            (delegate as? Resolver26Observer)?.onResolverApplication(observation)
            applicationObserver(observation)
        }
    }
}

// Returns the positive externally configured worker count, defaulting to one.
internal fun configuredResolver26ThreadCount(): Int {
    val configured: String =
        System.getProperty(RESOLVER26_THREAD_COUNT_PROPERTY)
            ?: System.getenv(RESOLVER26_THREAD_COUNT_ENVIRONMENT)
            ?: "1"
    return configured.toIntOrNull()
        ?.takeIf { threadCount -> threadCount > 0 }
        ?: error(
            "$RESOLVER26_THREAD_COUNT_PROPERTY/$RESOLVER26_THREAD_COUNT_ENVIRONMENT " +
                "must be a positive integer: $configured",
        )
}

// Returns the process-scoped fixed dispatcher selected for Resolver26 requests.
internal fun resolver26CoroutineContext(): CoroutineContext =
    Resolver26Dispatchers.dispatcher(configuredResolver26ThreadCount())

// Retains one daemon-backed dispatcher for each configured worker count used in this JVM.
private object Resolver26Dispatchers {
    private val dispatchers = ConcurrentHashMap<Int, CoroutineDispatcher>()

    // Returns the existing dispatcher for this count or creates it exactly once.
    fun dispatcher(threadCount: Int): CoroutineDispatcher =
        dispatchers.computeIfAbsent(threadCount) { configuredThreadCount ->
            Executors
                .newFixedThreadPool(
                    configuredThreadCount,
                    Resolver26ThreadFactory(configuredThreadCount),
                ).asCoroutineDispatcher()
        }
}

// Names daemon workers so profilers can isolate Resolver26 execution.
private class Resolver26ThreadFactory(
    private val threadCount: Int,
) : ThreadFactory {
    private val nextThread = AtomicInteger()

    // Creates one daemon worker with a stable pool-specific name.
    override fun newThread(runnable: Runnable): Thread =
        Thread(
            runnable,
            "resolver26-$threadCount-${nextThread.incrementAndGet()}",
        ).apply {
            isDaemon = true
        }
}
