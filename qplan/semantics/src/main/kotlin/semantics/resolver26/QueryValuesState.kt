package semantics.resolver26

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import model.EngineObjectOrErrorData
import model.Promise
import model.ResolverOccurrenceId

/** Resolver26 readiness state for operational Query-fragment inputs. */
internal class QueryValuesState {
    private val values =
        ConcurrentHashMap<ResolverOccurrenceId, Promise<EngineObjectOrErrorData>>()

    fun declare(resolverOccurrenceId: ResolverOccurrenceId) {
        check(values.putIfAbsent(resolverOccurrenceId, Promise.ofDeferred()) == null) {
            "Resolver26 Query value was declared twice for $resolverOccurrenceId"
        }
    }

    fun complete(
        resolverOccurrenceId: ResolverOccurrenceId,
        value: EngineObjectOrErrorData,
    ): Boolean = values.getValue(resolverOccurrenceId).complete(value)

    fun cancel(
        resolverOccurrenceId: ResolverOccurrenceId,
        cause: CancellationException,
    ): Boolean = values.getValue(resolverOccurrenceId).cancel(cause)

    suspend fun fetch(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): EngineObjectOrErrorData = values.getValue(resolverOccurrenceId).await()
}
