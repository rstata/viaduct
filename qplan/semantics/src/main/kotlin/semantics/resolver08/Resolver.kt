package semantics.resolver08

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.registry.successorDemand
import semantics.DepthFirstReactor
import semantics.ReactorEventObserver
import semantics.SelectionCompletion
import semantics.SelectionCompleter

/**
 * Resolves [selections] through a depth-first work queue with selective resolver applications.
 * Whether the results contain only the necessary OER nodes has not been proved.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object =
    resolve(selections, eventObserver = {})

context(world: Assumptions)
internal fun Value.Object.resolve(
    selections: SelectionForest,
    eventObserver: ReactorEventObserver,
): EngineResult.Object {
    val selectionCompleter =
        SelectionCompleter { selections ->
            SelectionCompletion(
                selections = selections.successorDemand(),
                selective = true,
            )
        }
    return context(selectionCompleter) {
        DepthFirstReactor(
            source = this@resolve,
            selections = selections,
            eventObserver = eventObserver,
        ).resolve()
    }
}
