package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.Value
import model.applicableGroundSelections
import model.groundKey
import model.localizeTopLevelSelectionStamps

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
fun EngineResult.Object.conformsToSelections(selections: SelectionForest): Boolean =
    conformsToSelectionsAt(selections, emptyList())

context(world: Assumptions)
fun EngineResult.Object.conformsToSelections(selections: ObjectSelectionForest): Boolean {
    selections.byGroundKey()
    return type == selections.type && conformsToSelectionsAt(selections, emptyList())
}

// Checks selections rooted at an OER whose exact absolute path is supplied by the caller.
context(world: Assumptions)
fun EngineResult.Object.conformsToSelectionsAt(
    selections: SelectionForest,
    path: List<PathComponent>,
): Boolean = objectConformsToSelections(selections, path)

context(world: Assumptions)
private fun EngineResult.Object.objectConformsToSelections(
    selections: SelectionForest,
    path: List<PathComponent>,
): Boolean =
    selections.applicableGroundSelections(type).byGroundKey().values.all { selection ->
        val key = selection.groundKey()
        key in keys &&
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
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object ->
            objectConformsToSelections(
                selections = selections.localizeTopLevelSelectionStamps(path),
                path = path,
            )
        is EngineResult.List ->
            indices.all { index ->
                get(index).getValue().get().engineResultConformsToSelections(
                    selections = selections,
                    path = path + Value.ListIndex.of(index),
                )
            }
    }
