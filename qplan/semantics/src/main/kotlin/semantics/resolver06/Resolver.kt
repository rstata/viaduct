package semantics.resolver06

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.DepthFirstReactor
import semantics.DepthFirstTaskObserver
import semantics.SelectionCompletion
import semantics.SelectionCompleter

/**
 * Resolves [selections] through a depth-first work queue when resolver object fragments are empty,
 * except for generated `T$Bridge.$node` fragments that select passive sibling `$id`. Results are
 * non-selective and may contain more OER nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object =
    resolve(selections, taskObserver = {})

context(world: Assumptions)
internal fun Value.Object.resolve(
    selections: SelectionForest,
    taskObserver: DepthFirstTaskObserver,
): EngineResult.Object {
    val selectionCompleter =
        SelectionCompleter { selections ->
            SelectionCompletion(selections, selective = false)
        }
    return context(selectionCompleter) {
        DepthFirstReactor(
            source = this@resolve,
            selections = selections,
            taskObserver = taskObserver,
        ).resolve()
    }
}
