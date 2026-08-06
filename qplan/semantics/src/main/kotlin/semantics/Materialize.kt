package semantics

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.SelectionForest
import model.Value
import model.merge
import model.objectKey

/**
 * Materializes the object value selected by [fragment] from this result.
 *
 * This operation is defined when this result contains every cell selected by [fragment] and every
 * selection applicable at an object visited by this operation contains no [Value.Variable] in its
 * key arguments.
 */
context(world: Assumptions)
fun EngineResult.Object.materialize(fragment: Fragment): Value.Object =
    materializeSelectedObjectValue(fragment.subselections)

context(world: Assumptions)
private fun EngineResult.Object.materializeSelectedObjectValue(
    selections: SelectionForest,
): Value.Object {
    val selectedFields =
        selections
            .merge(type)
            .groupBy { selection -> selection.objectKey(type) }
            .mapValues { (key, mergedSelections) ->
                fetch(key).value.materializeEngineResultValue(
                    mergedSelections.single().subselections,
                )
            }

    return Value.Object.of(type, selectedFields)
}

context(world: Assumptions)
private fun EngineResult?.materializeEngineResultValue(
    selections: SelectionForest,
): Value.Output? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.Object -> materializeSelectedObjectValue(selections)
        is EngineResult.List ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values =
                    map { cell ->
                        cell.value.materializeEngineResultValue(selections)
                    },
            )
    }
