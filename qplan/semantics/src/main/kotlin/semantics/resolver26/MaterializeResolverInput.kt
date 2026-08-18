package semantics.resolver26

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectMaterializeSelection
import model.PathComponent
import model.Selection
import model.SimpleEngineResult
import model.Stamp
import model.Value
import model.fetchBindings
import model.localizeTopLevelSelectionStamps
import model.materializedFieldKey
import model.selectionForestOf
import model.toValue
import model.unionOutput
import semantics.RuntimeSupport

// Returns a resolver-visible input object, projecting stamped storage keys to ordinary keys.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
internal suspend fun ObjectEngineResult.materializeResolverInput(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Object =
    materializeSelectedObject(
        selections = selections,
        reader = reader,
        resultPath = resultPath,
        selectionPath = emptyList(),
    )

// Materializes selected OER values at their stored paths, then unions them under visible keys.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun ObjectEngineResult.materializeSelectedObject(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
    selectionPath: List<PathComponent>,
): Value.Object {
    val selectedValues =
        linkedMapOf<String, Pair<model.Schema.ObjectField, Value.Output?>>()
    selections.collect(type).byResponseKey().values.forEach { selection ->
        val storedGroundKey = selection.materializedGroundKey(selectionPath)
        val visibleGroundKey: ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                field = storedGroundKey.field,
                arguments = storedGroundKey.arguments,
            )
        val materializedKey = visibleGroundKey.materializedFieldKey()
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
        selectedValues[materializedKey] =
            if (materializedKey !in selectedValues) {
                storedGroundKey.field to selectedValue
            } else {
                storedGroundKey.field to
                    selectedValues
                        .getValue(materializedKey)
                        .second
                        .unionOutput(selectedValue)
            }
    }
    return Value.Object.of(
        type = type,
        fields =
            selectedValues.map { (key, fieldAndValue) ->
                Value.Object.FieldValue.of(key, fieldAndValue.first, fieldAndValue.second)
            },
    )
}

context(world: Assumptions)
private suspend fun ObjectMaterializeSelection.materializedGroundKey(
    selectionPath: List<PathComponent>,
): ObjectEngineResult.GroundKey {
    val arguments = key.arguments.fetchBindings(key.field.arguments)
    val stamp = key.stamp as? Stamp.Occurrence
    val groundedKey =
        if (stamp == null) {
            ObjectEngineResult.GroundKey.of(key.field, arguments)
        } else {
            ObjectEngineResult.GroundKey.of(stamp, key.field, arguments)
        }
    if (selectionPath.isEmpty()) return groundedKey
    val localizedKey =
        selectionForestOf(
            Selection.of(
                key = groundedKey,
                possibleTypes = setOf(groundedKey.field.containingType),
                subselections = selectionForestOf(),
            ),
        ).localizeTopLevelSelectionStamps(selectionPath)
        .single()
        .key
    return localizedKey as? ObjectEngineResult.GroundKey
        ?: error("Localized materialize key is not ground: $localizedKey")
}

// Recursively materializes one selected engine result while preserving null, error, and list shape.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun EngineResult?.materializeSelectedValue(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Output? {
    return when (this) {
        null -> null
        ErrorEngineResult -> Value.Error
        is SimpleEngineResult -> toValue()
        is ObjectEngineResult -> {
            materializeSelectedObject(
                selections = selections,
                reader = reader,
                resultPath = resultPath,
                selectionPath = resultPath,
            )
        }
        is ListEngineResult ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values =
                    indices.map { index ->
                        get(index).getValue().await().materializeSelectedValue(
                            selections = selections,
                            reader = reader,
                            resultPath = resultPath + ListEngineResult.Index.of(index),
                        )
                    },
            )
    }
}
