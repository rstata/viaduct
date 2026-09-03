package semantics.resolver26

import java.util.concurrent.ConcurrentHashMap
import model.ObjectEngineResult
import model.Promise

/** Resolver26 readiness state for the binding domain of each object-result occurrence. */
internal class BindingDeclarationsState {
    private val declarationsByObject =
        ConcurrentHashMap<ObjectEngineResult, Promise<Unit>>()

    suspend fun awaitBindingsDeclared(target: ObjectEngineResult) {
        declarationsByObject
            .computeIfAbsent(target) { Promise.ofDeferred() }
            .await()
    }

    fun markBindingsDeclared(target: ObjectEngineResult) {
        declarationsByObject
            .computeIfAbsent(target) { Promise.ofDeferred() }
            .complete(Unit)
    }
}
