package semantics.resolver07

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.registry.successorBoundaryDemand
import semantics.DepthFirstReactor
import semantics.ReactorEventObserver
import semantics.SelectionCompletion
import semantics.SelectionCompleter

/**
 * Resolves [selections] through a depth-first work queue with non-selective resolver applications.
 * Results may contain more OER nodes than are strictly necessary to resolve the query.
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
                selections = selections.successorBoundaryDemand(),
                selective = false,
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
