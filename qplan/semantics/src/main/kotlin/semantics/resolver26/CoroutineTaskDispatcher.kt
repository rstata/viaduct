package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import semantics.shared.SharedFieldPublicationOccurrence
import semantics.shared.SharedTaskDispatcher

/** Owns the two permitted request-root coroutine kinds for Resolver21-23 and Resolver26. */
internal class CoroutineTaskDispatcher<O : CoroutineOrchestrationTask<*>, F : SharedFieldPublicationOccurrence<*, *>>(
    private val requestScope: CoroutineScope,
    private val runFieldResolver: suspend (F, CoroutineScope) -> Unit,
    private val cancelFieldResolver: (F, CancellationException) -> Unit = { publication, cause ->
        publication.publicationCell.cancelValue(cause)
    },
) : SharedTaskDispatcher<O, F> {
    override fun dispatchOrchestrator(task: O) {
        task.checkDispatch()
        if (task.hasActiveWork) {
            // Installation remains synchronous even when the coroutine dispatcher queues execution.
            requestScope.launch(start = CoroutineStart.UNDISPATCHED) { task.run() }
        } else {
            task.run()
        }
    }

    override fun dispatchFieldResolver(publication: F) {
        requestScope.launch {
            runFieldResolver(publication, this)
        }.invokeOnCompletion { cause ->
            // Also terminates owned promises when cancellation prevents task entry.
            if (cause is CancellationException) cancelFieldResolver(publication, cause)
        }
    }
}
