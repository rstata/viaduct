package semantics.resolver02

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.registry.successorBoundaryDemand
import semantics.SelectionCompletion
import semantics.SelectionCompleter
import semantics.resolve

/**
 * Resolves [selections] with non-selective resolver applications. Results may contain more OER
 * nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    val selectionCompleter =
        SelectionCompleter { selections ->
            SelectionCompletion(
                selections = selections.successorBoundaryDemand(),
                selective = false,
            )
        }
    return context(selectionCompleter) {
        resolve(
            path = emptyList(),
            selections = selections,
            resolved = EngineResult.Object.of(type, emptyMap()),
        )
    }
}
