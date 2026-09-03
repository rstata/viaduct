package semantics.resolver26

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.Promise
import model.ResolverOccurrenceId
import semantics.shared.CycleChecker
import viaduct.engine.api.EngineObjectData

/** Owns request lifetime without using task completion as cross-task readiness. */
internal class Resolver26Support(
    internal val requestScope: CoroutineScope,
    private val applicationObserver: Resolver26ApplicationObserver,
    cycleChecker: CycleChecker,
) : CycleChecker by cycleChecker {
    private val bindingsDeclaredByObject =
        ConcurrentHashMap<ObjectEngineResult, Promise<Unit>>()
    private val queryValues =
        ConcurrentHashMap<ResolverOccurrenceId, Promise<EngineObjectData.Sync>>()

    fun observeApplication(observation: Resolver26ApplicationObservation) {
        applicationObserver(observation)
    }

    suspend fun awaitBindingsDeclared(target: ObjectEngineResult) {
        bindingsDeclaredByObject
            .computeIfAbsent(target) {
                Promise.ofDeferred()
            }.await()
    }

    fun markBindingsDeclared(target: ObjectEngineResult) {
        bindingsDeclaredByObject
            .computeIfAbsent(target) {
                Promise.ofDeferred()
            }.complete(Unit)
    }

    fun declareQueryValue(resolverOccurrenceId: ResolverOccurrenceId) {
        check(queryValues.putIfAbsent(resolverOccurrenceId, Promise.ofDeferred()) == null) {
            "Resolver26 Query value was declared twice for $resolverOccurrenceId"
        }
    }

    fun completeQueryValue(
        resolverOccurrenceId: ResolverOccurrenceId,
        value: EngineObjectData.Sync,
    ) = queryValues.getValue(resolverOccurrenceId).complete(value)

    suspend fun fetchQueryValue(
        resolverOccurrenceId: ResolverOccurrenceId,
    ): EngineObjectData.Sync = queryValues.getValue(resolverOccurrenceId).await()
}
