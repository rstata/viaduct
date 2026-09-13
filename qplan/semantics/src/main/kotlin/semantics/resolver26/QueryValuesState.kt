package semantics.resolver26

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import model.EngineObjectOrErrorData
import model.Promise
import model.ResolverOccurrenceId
import viaduct.engine.api.EngineObjectData

/** Resolver26 readiness state for operational Query-fragment inputs. */
internal class QueryValuesState {
    private val values =
        ConcurrentHashMap<ResolverOccurrenceId, Promise<EngineObjectOrErrorData>>()

    fun declare(resolverOccurrenceId: ResolverOccurrenceId) {
        check(values.putIfAbsent(resolverOccurrenceId, Promise.ofDeferred()) == null) {
            "Resolver26 Query value was declared twice for $resolverOccurrenceId"
        }
    }

    fun launchProducer(
        scope: CoroutineScope,
        resolverOccurrenceId: ResolverOccurrenceId,
        producer: suspend () -> EngineObjectData.Sync,
    ): Job {
        // Resolve the declaration before launch so an undeclared producer fails synchronously and
        // cancellation before coroutine entry can terminate this exact promise.
        val value = values.getValue(resolverOccurrenceId)
        return scope
            .launch {
                val produced =
                    try {
                        EngineObjectOrErrorData.of(producer())
                    } catch (cause: Exception) {
                        currentCoroutineContext().ensureActive()
                        EngineObjectOrErrorData.of(model.EngineErrorData.of(cause))
                    }
                check(value.complete(produced)) {
                    "Resolver26 Query value was already completed for $resolverOccurrenceId"
                }
            }.apply {
                invokeOnCompletion { cause ->
                    if (cause is CancellationException) value.cancel(cause)
                }
            }
    }

    suspend fun fetch(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): EngineObjectOrErrorData = values.getValue(resolverOccurrenceId).await()
}
