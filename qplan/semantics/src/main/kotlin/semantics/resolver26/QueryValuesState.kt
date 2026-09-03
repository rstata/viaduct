package semantics.resolver26

import java.util.concurrent.ConcurrentHashMap
import model.Promise
import model.ResolverOccurrenceId
import viaduct.engine.api.EngineObjectData

/** Resolver26 readiness state for operational Query-fragment inputs. */
internal class QueryValuesState {
    private val values =
        ConcurrentHashMap<ResolverOccurrenceId, Promise<EngineObjectData.Sync>>()

    fun declare(resolverOccurrenceId: ResolverOccurrenceId) {
        check(values.putIfAbsent(resolverOccurrenceId, Promise.ofDeferred()) == null) {
            "Resolver26 Query value was declared twice for $resolverOccurrenceId"
        }
    }

    fun complete(
        resolverOccurrenceId: ResolverOccurrenceId,
        value: EngineObjectData.Sync,
    ) = values.getValue(resolverOccurrenceId).complete(value)

    suspend fun fetch(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): EngineObjectData.Sync = values.getValue(resolverOccurrenceId).await()
}
