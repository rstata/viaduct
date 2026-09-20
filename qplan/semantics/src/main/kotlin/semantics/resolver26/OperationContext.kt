package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import semantics.shared.CycleCheckState
import semantics.shared.SharedResolverObserver
import semantics.shared.SharedOperationContext

/**
 * One Resolver26 execution scope: scheduling, observation, cycle checking, and binding readiness.
 * Child execution scopes share the logical operation's configuration and mutable state references.
 */
internal interface OperationContext : SharedOperationContext<CoroutineTaskDispatcher<OrchestrationTask, FieldPublicationOccurrence>> {
    val cycleChecker: CycleCheckState
    val bindingsState: BindingDeclarationsState

    /** Derives nested execution under its calling field task while retaining operation state. */
    fun forChildScope(requestScope: CoroutineScope): OperationContext =
        create(this, requestScope, resolverObserver, cycleChecker, bindingsState)

    companion object {
        fun create(
            base: SharedOperationContext<*>,
            requestScope: CoroutineScope,
            resolverObserver: SharedResolverObserver,
            cycleChecker: CycleCheckState = CycleCheckState.create(),
            bindingsState: BindingDeclarationsState = BindingDeclarationsState(),
        ): OperationContext {
            val operationDelegate = SharedOperationContext.create(
                world = base.world,
                variableBindings = base.variableBindings,
                resolverObserver = resolverObserver,
                dispatcher = CoroutineTaskDispatcher<OrchestrationTask, FieldPublicationOccurrence>(
                    requestScope = requestScope,
                    runFieldResolver = FieldResolverTask::execute,
                    cancelFieldResolver = FieldResolverTask::cancel,
                ),
            )
            return object : OperationContext,
                SharedOperationContext<CoroutineTaskDispatcher<OrchestrationTask, FieldPublicationOccurrence>> by operationDelegate {
                override val cycleChecker = cycleChecker
                override val bindingsState = bindingsState
            }
        }
    }
}
