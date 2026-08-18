package semantics.resolver26

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import model.PathComponent
import model.Schema
import model.SelectionForest
import model.Stamp
import model.Value
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

internal const val RESOLVER26_THREAD_COUNT_PROPERTY = "resolver26.thread.count"
internal const val RESOLVER26_THREAD_COUNT_ENVIRONMENT = "RESOLVER26_THREAD_COUNT"

internal data class Resolver26ApplicationObservation(
    val occurrencePath: List<PathComponent>,
    val field: Schema.ObjectField,
    val input: Value.Object,
    val arguments: Value.Arguments,
    val suppliedDemand: SelectionForest,
    val variableArgumentCount: Int,
    val occurrenceStamp: Stamp.Occurrence?,
    val variableSourceSelectionStamps: Set<Stamp.Occurrence>,
)

internal typealias Resolver26ApplicationObserver = (Resolver26ApplicationObservation) -> Unit

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
