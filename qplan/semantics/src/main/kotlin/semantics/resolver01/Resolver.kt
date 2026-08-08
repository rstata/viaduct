package semantics.resolver01

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.SelectionCompletion
import semantics.SelectionCompleter
import semantics.orchestrateKeys

/**
 * Resolves [selections] when resolver object fragments are empty, except for generated Node-loader
 * fragments that select synthetic `foo$id` or `foo$ids` bridge fields. Results are non-selective
 * and may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    val selectionCompleter =
        SelectionCompleter { selections ->
            SelectionCompletion(selections, selective = false)
        }
    return context(selectionCompleter) {
        orchestrateKeys(
            path = emptyList(),
            selections = selections,
            resolved = EngineResult.Object.of(type, emptyMap()),
        )
    }
}
