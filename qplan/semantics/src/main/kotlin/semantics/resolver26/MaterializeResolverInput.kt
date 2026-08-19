package semantics.resolver26

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.PathComponent
import model.Schema
import model.TypeExpr
import model.Value
import model.toValue
import semantics.RuntimeSupport
import semantics.materializedGroundKey

// Returns a resolver-visible input object collected by GraphQL response key.
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

// Materializes selected OER values at their exact stored paths.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun ObjectEngineResult.materializeSelectedObject(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
    selectionPath: List<PathComponent>,
): Value.Object {
    val selectedValues =
        linkedMapOf<String, Pair<model.Schema.ObjectField, Value.Output?>>()
    selections.collect(type).byResponseKey().forEach { (responseKey, selection) ->
        val storedGroundKey = selection.materializedGroundKey(selectionPath)
        val cell = reserveCell(storedGroundKey)
        diagnosticInstrumentation.cycleCheck(reader, cell)
        val selectedValue: Value.Output? =
            cell
                .reserveValue()
                .await()
                .materializeSelectedValue(
                    expectedType = storedGroundKey.field.typeExpr,
                    selections = selection.subselections,
                    reader = reader,
                    resultPath = resultPath + storedGroundKey,
                )
        selectedValues[responseKey] = storedGroundKey.field to selectedValue
    }
    return Value.Object.of(
        type = type,
        fields =
            selectedValues.map { (key, fieldAndValue) ->
                Value.Object.FieldValue.of(key, fieldAndValue.first, fieldAndValue.second)
            },
    )
}

// Recursively materializes one selected engine result while preserving null, error, and list shape.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun EngineResult?.materializeSelectedValue(
    expectedType: TypeExpr<Schema.OutputType>,
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Output? {
    return when (this) {
        null -> null
        ErrorEngineResult -> Value.Error
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
                            expectedType = typeExpr,
                            selections = selections,
                            reader = reader,
                            resultPath = resultPath + ListEngineResult.Index.of(index),
                        )
                    },
            )
        else -> toValue(expectedType.baseType as Schema.SimpleType)
    }
}
