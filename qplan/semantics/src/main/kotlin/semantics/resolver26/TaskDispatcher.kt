package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import semantics.shared.SharedTaskDispatcher

/**
 * Dispatches prepared orchestration tasks and field contexts on the Resolver26 request scope.
 * Helper coroutines retain their field-task scope; this dispatcher exposes no generic launch.
 */
internal class TaskDispatcher(private val requestScope: CoroutineScope) : SharedTaskDispatcher<OrchestrationTask, FieldResolverContext> {
    override fun dispatchOrchestrator(task: OrchestrationTask) {
        task.checkDispatch()
        if (task.hasActiveWork) {
            // Installation remains synchronous even when the coroutine dispatcher queues execution.
            requestScope.launch(start = CoroutineStart.UNDISPATCHED) { task.run() }
        } else {
            task.run()
        }
    }

    override fun dispatchFieldResolver(context: FieldResolverContext) {
        requestScope.launch {
            FieldResolverTask.execute(context, this)
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) FieldResolverTask.cancel(context, cause)
        }
    }
}
