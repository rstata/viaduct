package semantics

import model.Assumptions
import model.EngineResult
import model.GroundSelectionForest
import model.SelectionForest
import model.Value
import model.mergeToGround

/**
 * Materializes the object value selected by [selections] from this result.
 *
 * This operation is defined when this result contains every cell selected by [selections] and every
 * selection applicable at an object visited by this operation contains no [Value.Variable] in its
 * key arguments.
 */
context(world: Assumptions)
fun EngineResult.Object.materialize(selections: GroundSelectionForest): Value.Object {
    require(type == selections.type) {
        "Selection type ${selections.type.typeName} does not match result type ${type.typeName}"
    }
    return materializeSelectedObjectValue(selections)
}

context(world: Assumptions)
private fun EngineResult.Object.materializeSelectedObjectValue(
    selections: SelectionForest,
): Value.Object =
    materializeSelectedObjectValue(selections.mergeToGround(type))

context(world: Assumptions)
private fun EngineResult.Object.materializeSelectedObjectValue(
    selections: GroundSelectionForest,
): Value.Object {
    val selectedFields =
        selections
            .byKey()
            .mapValues { (key, selection) ->
                fetch(key).value.materializeEngineResultValue(
                    selection.subselections,
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
