package semantics.resolver03

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.registry.successorDemand
import semantics.SelectionCompletion
import semantics.SelectionCompleter
import semantics.orchestrateKeys

/**
 * Resolves [selections] with selective resolver applications. Whether the results contain only the
 * necessary OER nodes has not been proved.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    val selectionCompleter =
        SelectionCompleter { selections ->
            SelectionCompletion(
                selections = selections.successorDemand(),
                selective = true,
            )
        }
    return context(selectionCompleter) {
        orchestrateKeys(
            path = emptyList(),
            selections = selections,
            resolved = EngineResult.Object.of(type, emptyMap(), mutable = true),
        )
    }
}
