package semantics

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.SelectionForest
import model.Value
import semantics.correctresolution.concreteObjectKey

/**
 * Materializes the object value selected by [fragment] from this result.
 *
 * This operation is defined when this result contains every cell selected by [fragment].
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
            .filter { selection -> type in selection.possibleTypes }
            .groupBy { selection -> selection.concreteObjectKey(type) }
            .mapValues { (key, fieldSelections) ->
                val subselections =
                    fieldSelections.flatMap { selection -> selection.subselections }
                fetch(key).value.materializeEngineResultValue(subselections)
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
