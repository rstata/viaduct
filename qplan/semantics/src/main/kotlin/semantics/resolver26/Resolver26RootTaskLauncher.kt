package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches the explicitly permitted Resolver26 request-root task kinds.
 *
 * This type intentionally does not expose its raw [CoroutineScope] or a generic `launch` operation.
 * Query producers, provider readers, binding producers, and other helper coroutines must launch on
 * their owning task's scope instead.
 */
internal class Resolver26RootTaskLauncher(
    private val requestScope: CoroutineScope,
) {
    // The root is a placeholder for future asynchronous orchestration. Today its body does not
    // suspend, so enter immediately and expose its install/freeze work even on a queued dispatcher.
    fun launchObjectOrchestrationTask(
        block: suspend CoroutineScope.() -> Unit,
    ): Job =
        requestScope.launch(start = CoroutineStart.UNDISPATCHED, block = block)

    fun launchFieldResolverTask(
        block: suspend CoroutineScope.() -> Unit,
    ): Job = requestScope.launch(block = block)
}
