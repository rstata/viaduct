package semantics

import model.Assumptions
import model.EngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.Value
import model.applicableGroundSelections

/**
 * Materializes the object value selected by [selections] from this result.
 *
 * [reader] is the exact root-relative coordinate of the resolver consuming the materialized value.
 *
 * This operation is defined when this result contains every value promise selected by [selections] and every selection applicable at an object visited by this operation contains no [Value.Variable] in its key arguments.
 */
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal suspend fun EngineResult.Object.materialize(
    selections: ObjectSelectionForest,
    reader: List<PathComponent>,
): Value.Object {
    require(type == selections.type) {
        "Selection type ${selections.type.typeName} does not match result type ${type.typeName}"
    }
    return materializeSelectedObjectValue(selections, reader)
}

context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun EngineResult.Object.materializeSelectedObjectValue(
    selections: SelectionForest,
    reader: List<PathComponent>,
): Value.Object =
    materializeSelectedObjectValue(
        selections = selections.applicableGroundSelections(type),
        reader = reader,
    )

context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun EngineResult.Object.materializeSelectedObjectValue(
    selections: ObjectSelectionForest,
    reader: List<PathComponent>,
): Value.Object {
    val selectedFields = linkedMapOf<Value.GroundKey, Value.Output?>()
    selections.byGroundKey().forEach { (key, selection) ->
        val promise = getValue(key)
        runtimeSupport.cycleCheck(reader, this, key)
        selectedFields[key] =
            promise.await().materializeEngineResultValue(
                selections = selection.subselections,
                reader = reader,
            )
    }

    return Value.Object.of(type, selectedFields)
}

context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun EngineResult?.materializeEngineResultValue(
    selections: SelectionForest,
    reader: List<PathComponent>,
): Value.Output? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.Object ->
            materializeSelectedObjectValue(
                selections = selections,
                reader = reader,
            )
        is EngineResult.List ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values =
                    materializeValues(
                        selections = selections,
                        reader = reader,
                    ),
            )
    }

context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun EngineResult.List.materializeValues(
    selections: SelectionForest,
    reader: List<PathComponent>,
): kotlin.collections.List<Value.Output?> {
    val materialized = mutableListOf<Value.Output?>()
    indices.forEach { index ->
        materialized +=
            get(index).materializeEngineResultValue(
                selections = selections,
                reader = reader,
            )
    }
    return materialized
}
