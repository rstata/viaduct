package semantics.resolver26

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import model.ObjectEngineResult
import model.Promise
import semantics.ResolverSupport

/** Owns request lifetime without using task completion as cross-task readiness. */
internal class Resolver26Support(
    private val requestScope: CoroutineScope,
    private val applicationObserver: Resolver26ApplicationObserver,
    resolverSupport: ResolverSupport,
) : ResolverSupport by resolverSupport {
    private val bindingsDeclaredByObject =
        ConcurrentHashMap<ObjectEngineResult, Promise<Unit>>()

    fun launchObjectOrchestrationTask(task: ObjectOrchestrationTask) {
        requestScope.launch {
            task.run()
        }
    }

    fun launchFieldResolverTask(task: FieldResolverTask) {
        requestScope.launch {
            task.run()
        }
    }

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
}
