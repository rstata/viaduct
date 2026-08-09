package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ObjectSelectionForest
import model.SelectionForest
import model.Value
import model.applicableGroundSelections
import model.groundKey

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
    objectConformsToSelections(selections)

context(world: Assumptions)
fun EngineResult.Object.conformsToSelections(selections: ObjectSelectionForest): Boolean {
    selections.byGroundKey()
    return type == selections.type && objectConformsToSelections(selections)
}

context(world: Assumptions)
private fun EngineResult.Object.objectConformsToSelections(
    selections: SelectionForest,
): Boolean =
    selections.applicableGroundSelections(type).byGroundKey().values.all { selection ->
        val key = selection.groundKey()
        key in keys &&
            getValue(key).get().engineResultConformsToSelections(selection.subselections)
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToSelections(
    selections: SelectionForest,
): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object -> objectConformsToSelections(selections)
        is EngineResult.List ->
            all { value -> value.engineResultConformsToSelections(selections) }
    }
