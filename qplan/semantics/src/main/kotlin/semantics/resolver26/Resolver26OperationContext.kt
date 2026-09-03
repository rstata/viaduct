package semantics.resolver26

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.Promise
import semantics.shared.CycleChecker
import semantics.shared.OperationContext

/** Request-local state and observation boundary specific to Resolver26. */
internal class Resolver26OperationContext(
    base: OperationContext,
    val requestScope: CoroutineScope,
    override val resolverObserver: Resolver26Observer,
    cycleChecker: CycleChecker = CycleChecker.create(),
    val queryValuesState: QueryValuesState = QueryValuesState(),
) : OperationContext(
        world = base.world,
        variableBindingsState = base.variableBindingsState,
        resolverObserver = resolverObserver,
    ),
    CycleChecker by cycleChecker {
    private val bindingsDeclaredByObject =
        ConcurrentHashMap<ObjectEngineResult, Promise<Unit>>()

    suspend fun awaitBindingsDeclared(target: ObjectEngineResult) {
        bindingsDeclaredByObject
            .computeIfAbsent(target) { Promise.ofDeferred() }
            .await()
    }

    fun markBindingsDeclared(target: ObjectEngineResult) {
        bindingsDeclaredByObject
            .computeIfAbsent(target) { Promise.ofDeferred() }
            .complete(Unit)
    }
}
