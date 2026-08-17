package semantics

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.SimpleEngineResult
import model.Value
import model.applicableGroundSelections
import model.localizeTopLevelSelectionStamps
import model.toValue
import model.unionOutput

/**
 * Materializes the object value selected by [selections] from this result.
 *
 * [reader] is the exact root-relative coordinate of the resolver consuming the materialized value.
 *
 * This operation is defined when this result contains every value promise selected by [selections] and every selection applicable at an object visited by this operation contains no [Value.Variable] in its key arguments.
 */
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal suspend fun ObjectEngineResult.materialize(
    selections: ObjectSelectionForest,
    reader: List<PathComponent>,
): Value.Object {
    require(type == selections.type) {
        "Selection type ${selections.type.typeName} does not match result type ${type.typeName}"
    }
    return materializeSelectedObjectValue(
        selections = selections,
        reader = reader,
        resultPath = reader.dropLast(1),
    )
}

// Materializes a selection forest rooted at one exact OER path.
context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun ObjectEngineResult.materializeSelectedObjectValue(
    selections: SelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Object =
    materializeSelectedObjectValue(
        selections = selections.applicableGroundSelections(type),
        reader = reader,
        resultPath = resultPath,
    )

// Materializes stored instances before projecting and unioning their visible GraphQL keys.
context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun ObjectEngineResult.materializeSelectedObjectValue(
    selections: ObjectSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Object {
    val selectedValues = linkedMapOf<ObjectEngineResult.GroundKey, Value.Output?>()
    selections.byGroundKey().forEach { (storedKey, selection) ->
        val visibleKey =
            ObjectEngineResult.GroundKey.of(
                field = storedKey.field,
                arguments = storedKey.arguments.fieldValues,
            )
        val cell = getCell(storedKey)
        val promise = cell.getValue()
        runtimeSupport.cycleCheck(reader, cell)
        val selectedValue =
            promise
                .await()
                .materializeEngineResultValue(
                    selections = selection.subselections,
                    reader = reader,
                    resultPath = resultPath + storedKey,
                )
        selectedValues[visibleKey] =
            if (visibleKey !in selectedValues) {
                selectedValue
            } else {
                selectedValues.getValue(visibleKey).unionOutput(selectedValue)
            }
    }
    return Value.Object.of(type, selectedValues)
}

// Recursively materializes one selected result while retaining its exact stored path.
context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun EngineResult?.materializeEngineResultValue(
    selections: SelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Output? =
    when (this) {
        null -> null
        ErrorEngineResult -> Value.Error
        is SimpleEngineResult -> toValue()
        is ObjectEngineResult ->
            materializeSelectedObjectValue(
                selections = selections.localizeTopLevelSelectionStamps(resultPath),
                reader = reader,
                resultPath = resultPath,
            )
        is ListEngineResult ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values =
                    materializeValues(
                        selections = selections,
                        reader = reader,
                        resultPath = resultPath,
                    ),
            )
    }

// Materializes each list element at a path containing its concrete list index.
context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun ListEngineResult.materializeValues(
    selections: SelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): kotlin.collections.List<Value.Output?> {
    val materialized = mutableListOf<Value.Output?>()
    indices.forEach { index ->
        materialized +=
            get(index).getValue().await().materializeEngineResultValue(
                selections = selections,
                reader = reader,
                resultPath = resultPath + ListEngineResult.Index.of(index),
            )
    }
    return materialized
}
