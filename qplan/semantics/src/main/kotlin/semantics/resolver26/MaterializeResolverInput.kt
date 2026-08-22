package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineOutputListData
import model.EngineObjectDataEntry
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.outputType
import model.PathComponent
import model.materializedEngineObjectDataOf
import model.toEngineOutputData
import semantics.RuntimeSupport
import semantics.materializedGroundKey
import viaduct.engine.api.EngineObjectData

// Returns a resolver-visible input object collected by GraphQL response key.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
internal suspend fun ObjectEngineResult.materializeResolverInput(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): EngineObjectData.Sync =
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
): EngineObjectData.Sync {
    val selectedValues =
        linkedMapOf<String, Pair<ViaductSchema.ObjectField, EngineOutputData?>>()
    selections.collect(type).byResponseKey().forEach { (responseKey, selection) ->
        val storedGroundKey = selection.materializedGroundKey(selectionPath)
        val cell = reserveCell(storedGroundKey)
        diagnosticInstrumentation.cycleCheck(reader, cell)
        val selectedValue: EngineOutputData? =
            cell
                .reserveValue()
                .await()
                .materializeSelectedValue(
                    expectedType = storedGroundKey.field.outputType,
                    selections = selection.subselections,
                    reader = reader,
                    resultPath = resultPath + storedGroundKey,
                )
        selectedValues[responseKey] = storedGroundKey.field to selectedValue
    }
    return materializedEngineObjectDataOf(
        schemaType = type,
        fields =
            selectedValues.map { (key, fieldAndValue) ->
                EngineObjectDataEntry.of(key, fieldAndValue.first, fieldAndValue.second)
            },
    )
}

// Recursively materializes one selected engine result while preserving null, error, and list shape.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun EngineResult?.materializeSelectedValue(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): EngineOutputData? {
    return when (this) {
        null -> null
        is ErrorEngineResult -> errorData
        is ObjectEngineResult -> {
            materializeSelectedObject(
                selections = selections,
                reader = reader,
                resultPath = resultPath,
                selectionPath = resultPath,
            )
        }
        is ListEngineResult -> {
            require(expectedType.isList)
            val values: EngineOutputListData =
                indices.map { index ->
                    get(index).getValue().await().materializeSelectedValue(
                        expectedType = typeExpr,
                        selections = selections,
                        reader = reader,
                        resultPath = resultPath + ListEngineResult.Index.of(index),
                    )
                }
            values
        }
        else -> toEngineOutputData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef)
    }
}
