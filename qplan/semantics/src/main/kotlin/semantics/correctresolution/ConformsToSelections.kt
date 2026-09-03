package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.merge
import semantics.shared.findStoredKey

/**
 * Whether this result contains every value required by [selections].
 *
 * At every object occurrence, selections are normalized against the runtime concrete object type
 * and current variable bindings before lookup. Null and error values stop recursive requirements.
 * Values not required by [selections] are permitted.
 *
 * This predicate trusts the selections' post-validation schema compatibility and the engine-result
 * carrier invariants established by its factories. It observes values, but not field or type
 * checks.
 *
 * This operation is defined only when applicable selection keys contain no unbound variables.
 */
context(world: Assumptions)
fun ObjectEngineResult.conformsToSelections(
    selections: SelectionForest,
): Boolean = conformsToSelectionsAt(selections, emptyList())

context(world: Assumptions)
fun ObjectEngineResult.conformsToSelections(
    selections: ObjectSelectionForest,
): Boolean =
    type == selections.type &&
        conformsToSelectionsAt(selections, emptyList())

// Checks selections rooted at an OER whose exact absolute path is supplied by the caller.
context(world: Assumptions)
fun ObjectEngineResult.conformsToSelectionsAt(
    selections: SelectionForest,
    path: List<PathComponent>,
): Boolean = objectConformsToSelections(selections, path)

context(world: Assumptions)
private fun ObjectEngineResult.objectConformsToSelections(
    selections: SelectionForest,
    path: List<PathComponent>,
): Boolean =
    selections.merge(type).byKey().values.all { selection ->
        val key = findStoredKey(selection.key)
        key != null &&
            getCell(key)
                .getValue()
                .get()
                .engineResultConformsToSelections(
                    selections = selection.subselections,
                    path = path + key,
                )
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToSelections(
    selections: SelectionForest,
    path: List<PathComponent>,
): Boolean =
    when (this) {
        null,
        is ErrorEngineResult,
        -> true

        is ObjectEngineResult ->
            objectConformsToSelections(
                selections = selections,
                path = path,
            )
        is ListEngineResult ->
            indices.all { index ->
                get(index).getValue().get().engineResultConformsToSelections(
                    selections = selections,
                    path = path + ListEngineResult.Index.of(index),
                )
            }
        else -> true
    }
