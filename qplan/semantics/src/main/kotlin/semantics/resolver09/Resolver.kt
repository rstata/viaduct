package semantics.resolver09

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.registry.successorDemand
import semantics.ReactorEventObserver
import semantics.SelectionCompletion
import semantics.SelectionCompleter

/**
 * Resolves [selections] through exact field-resolver-instance readiness with selective outputs.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object =
    resolve(
        selections = selections,
        eventObserver = {},
    )

context(world: Assumptions)
internal fun Value.Object.resolve(
    selections: SelectionForest,
    eventObserver: ReactorEventObserver = {},
): EngineResult.Object {
    val selectionCompleter =
        SelectionCompleter { selections ->
            SelectionCompletion(
                selections = selections.successorDemand(),
                selective = true,
            )
        }
    return context(selectionCompleter) {
        Reactor(
            source = this@resolve,
            selections = selections,
            eventObserver = eventObserver,
        )
    }
}
