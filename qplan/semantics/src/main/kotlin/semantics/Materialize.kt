package semantics

import model.Assumptions
import model.EngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.Value
import model.applicableGroundSelections
import model.localizeTopLevelSelectionStamps
import model.unionOutput

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
    return materializeSelectedObjectValue(
        selections = selections,
        reader = reader,
        resultPath = reader.dropLast(1),
    )
}

// Materializes a selection forest rooted at one exact OER path.
context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun EngineResult.Object.materializeSelectedObjectValue(
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
private suspend fun EngineResult.Object.materializeSelectedObjectValue(
    selections: ObjectSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Object {
    val selectedValues = linkedMapOf<Value.GroundKey, Value.Output?>()
    selections.byGroundKey().forEach { (storedKey, selection) ->
        val visibleKey =
            Value.GroundKey.of(
                field = storedKey.field,
                arguments = storedKey.arguments.fieldValues,
            )
        val promise = getValue(storedKey)
        runtimeSupport.cycleCheck(reader, this, storedKey)
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
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.Object ->
            materializeSelectedObjectValue(
                selections = selections.localizeTopLevelSelectionStamps(resultPath),
                reader = reader,
                resultPath = resultPath,
            )
        is EngineResult.List ->
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
private suspend fun EngineResult.List.materializeValues(
    selections: SelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): kotlin.collections.List<Value.Output?> {
    val materialized = mutableListOf<Value.Output?>()
    indices.forEach { index ->
        materialized +=
            get(index).materializeEngineResultValue(
                selections = selections,
                reader = reader,
                resultPath = resultPath + Value.ListIndex.of(index),
            )
    }
    return materialized
}
