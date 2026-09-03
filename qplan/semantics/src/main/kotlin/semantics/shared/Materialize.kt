package semantics.shared

import viaduct.graphql.schema.ViaductSchema

import model.EngineOutputData
import model.EngineOutputListData
import model.EngineObjectDataEntry
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.outputType
import model.ObjectMaterializeSelection
import model.PathComponent
import model.materializedEngineObjectDataOf
import model.toEngineOutputData
import viaduct.engine.api.EngineObjectData

/**
 * Materializes the object value selected by [selections] from this result.
 *
 * [reader] is the exact root-relative coordinate of the resolver consuming the materialized value.
 *
 * This operation is defined when this result contains every value promise selected by [selections]
 * and every applicable selection key is contextually grounded.
 */
context(operation: OperationContext, cycleChecker: CycleChecker)
internal suspend fun ObjectEngineResult.materialize(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
): EngineObjectData.Sync {
    return materializeSelectedObjectValue(
        selections = selections,
        reader = reader,
        resultPath = reader.dropLast(1),
    )
}

// Materializes a selection forest rooted at one exact OER path.
context(operation: OperationContext, cycleChecker: CycleChecker)
private suspend fun ObjectEngineResult.materializeSelectedObjectValue(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): EngineObjectData.Sync {
    val selectedValues =
        linkedMapOf<String, Pair<ViaductSchema.ObjectField, EngineOutputData?>>()
    selections.collect(type).byResponseKey().forEach { (responseKey, selection) ->
        val candidateKey = selection.materializedSymbolicKey()
        val storedKey = findStoredKey(candidateKey) ?: candidateKey
        val cell = getCell(storedKey)
        val promise = cell.getValue()
        cycleChecker.cycleCheck(reader, cell)
        val selectedValue =
            promise
                .await()
                .materializeEngineResultValue(
                    expectedType = storedKey.field.outputType,
                    selections = selection.subselections,
                    reader = reader,
                    resultPath = resultPath + storedKey,
                )
        selectedValues[responseKey] = storedKey.field to selectedValue
    }
    return materializedEngineObjectDataOf(
        schemaType = type,
        fields =
            selectedValues.map { (key, fieldAndValue) ->
                EngineObjectDataEntry.of(key, fieldAndValue.first, fieldAndValue.second)
            },
    )
}

context(operation: OperationContext)
private suspend fun ObjectMaterializeSelection.materializedSymbolicKey(
): ObjectEngineResult.ObjectKey {
    key.fetchGroundedArguments()
    return key
}

// Recursively materializes one selected result while retaining its exact stored path.
context(operation: OperationContext, cycleChecker: CycleChecker)
private suspend fun EngineResult?.materializeEngineResultValue(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): EngineOutputData? =
    when (this) {
        null -> null
        is ErrorEngineResult -> errorData
        is ObjectEngineResult ->
            materializeSelectedObjectValue(
                selections = selections,
                reader = reader,
                resultPath = resultPath,
            )
        is ListEngineResult -> {
            require(expectedType.isList)
            materializeValues(
                selections = selections,
                reader = reader,
                resultPath = resultPath,
            )
        }
        else -> toEngineOutputData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef)
    }

// Materializes each list element at a path containing its concrete list index.
context(operation: OperationContext, cycleChecker: CycleChecker)
private suspend fun ListEngineResult.materializeValues(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): EngineOutputListData {
    val materialized = mutableListOf<EngineOutputData?>()
    indices.forEach { index ->
        materialized +=
            get(index).getValue().await().materializeEngineResultValue(
                expectedType = typeExpr,
                selections = selections,
                reader = reader,
                resultPath = resultPath + ListEngineResult.Index.of(index),
            )
    }
    return materialized
}
