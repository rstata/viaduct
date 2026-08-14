package semantics.resolver26

import model.Assumptions
import model.EngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.Value
import model.fetchBindings
import model.localizeTopLevelSelectionStamps
import model.merge
import model.unionOutput
import semantics.RuntimeSupport

// Returns a resolver-visible input object, projecting stamped storage keys to ordinary keys.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
internal suspend fun EngineResult.Object.materializeResolverInput(
    selections: SelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Object =
    materializeSelectedObject(
        selections = selections.merge(type).fetchBindings(),
        reader = reader,
        resultPath = resultPath,
    )

// Materializes selected OER values at their stored paths, then unions them under visible keys.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun EngineResult.Object.materializeSelectedObject(
    selections: ObjectSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Object {
    val selectedValues = linkedMapOf<Value.GroundKey, Value.Output?>()
    selections.byGroundKey().forEach { (storedGroundKey, selection) ->
        val visibleGroundKey: Value.GroundKey =
            Value.GroundKey.of(
                field = storedGroundKey.field,
                arguments = storedGroundKey.arguments.fieldValues,
            )
        val cell = reserveCell(storedGroundKey)
        diagnosticInstrumentation.cycleCheck(reader, cell)
        val selectedValue: Value.Output? =
            cell
                .reserveValue()
                .await()
                .materializeSelectedValue(
                    selections = selection.subselections,
                    reader = reader,
                    resultPath = resultPath + storedGroundKey,
                )
        selectedValues[visibleGroundKey] =
            if (visibleGroundKey !in selectedValues) {
                selectedValue
            } else {
                selectedValues.getValue(visibleGroundKey).unionOutput(selectedValue)
            }
    }
    return Value.Object.of(
        type = type,
        fields = selectedValues,
    )
}

// Recursively materializes one selected engine result while preserving null, error, and list shape.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun EngineResult?.materializeSelectedValue(
    selections: SelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Output? {
    return when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.Object -> {
            // Bind through the owner's declared variables before restamping the ground child keys.
            val groundedDemand: ObjectSelectionForest =
                selections.merge(type).fetchBindings()
            val localizedGroundDemand: ObjectSelectionForest =
                groundedDemand
                    .localizeTopLevelSelectionStamps(resultPath)
                    .merge(type)
            materializeSelectedObject(
                selections = localizedGroundDemand,
                reader = reader,
                resultPath = resultPath,
            )
        }
        is EngineResult.List ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values =
                    indices.map { index ->
                        get(index).getValue().await().materializeSelectedValue(
                            selections = selections,
                            reader = reader,
                            resultPath = resultPath + Value.ListIndex.of(index),
                        )
                    },
            )
    }
}
