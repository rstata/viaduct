package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.objectKey

/**
 * Whether this result contains every cell required by [fragment].
 *
 * Each selection is first guarded by the runtime concrete object type. Applicable selection keys
 * are then specialized to that concrete type before lookup. Null and error values stop recursive
 * requirements. Cells not required by [fragment] are permitted.
 *
 * This predicate trusts the fragment's post-validation schema compatibility and the engine-result
 * carrier invariants established by its factories. It observes values, but not check components.
 *
 * This operation is defined only when applicable selection keys contain no variables.
 */
context(world: Assumptions)
fun EngineResult.Object.conformsToFragment(fragment: Fragment): Boolean =
    objectConformsToFragment(fragment.subselections)

context(world: Assumptions)
private fun EngineResult.Object.objectConformsToFragment(
    selections: SelectionForest,
): Boolean =
    selections.all { selection ->
        if (type !in selection.possibleTypes) {
            true
        } else {
            val key = selection.objectKey(type)
            key in keys &&
                fetch(key).value.engineResultConformsToFragment(selection.subselections)
        }
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToFragment(
    selections: SelectionForest,
): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object -> objectConformsToFragment(selections)
        is EngineResult.List ->
            all { cell -> cell.value.engineResultConformsToFragment(selections) }
    }
