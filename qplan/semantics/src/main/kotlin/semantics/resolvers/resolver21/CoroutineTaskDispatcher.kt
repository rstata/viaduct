package semantics.resolvers.resolver21

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import semantics.shared.SharedTaskDispatcher

/** Uses Resolver26's request-root ownership and synchronous field-installation boundary. */
internal class CoroutineTaskDispatcher(private val requestScope: CoroutineScope) : SharedTaskDispatcher<CoroutineOrchestrationTask, CoroutineFieldResolverContext> {
    override fun dispatchOrchestrator(task: CoroutineOrchestrationTask) {
        task.checkDispatch()
        if (task.hasActiveWork) {
            requestScope.launch(start = CoroutineStart.UNDISPATCHED) { task.run() }
        } else {
            task.run()
        }
    }

    override fun dispatchFieldResolver(context: CoroutineFieldResolverContext) {
        requestScope.launch { CoroutineFieldResolverTask.execute(context, this) }.invokeOnCompletion { cause ->
            if (cause is CancellationException) context.publicationCell.cancelValue(cause)
        }
    }
}
