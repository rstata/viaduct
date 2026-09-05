package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.EngineOutputData
import model.EngineOutputListData
import model.EngineObjectDataEntry
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectMaterializeSelection
import semantics.shared.fetchGroundedArguments
import model.outputType
import model.PathComponent
import model.materializedEngineObjectDataOf
import model.toEngineOutputData
import semantics.shared.CycleCheckState
import semantics.shared.OperationContext
import viaduct.engine.api.EngineObjectData

// Returns a resolver-visible input object collected by GraphQL response key.
context(operation: OperationContext, cycleChecker: CycleCheckState)
internal suspend fun ObjectEngineResult.materializeResolverInput(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): EngineObjectData.Sync =
    materializeSelectedObject(
        selections = selections,
        reader = reader,
        resultPath = resultPath,
    )

// Materializes selected OER values at their exact stored paths.
context(operation: OperationContext, cycleChecker: CycleCheckState)
private suspend fun ObjectEngineResult.materializeSelectedObject(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): EngineObjectData.Sync {
    val selectedValues =
        linkedMapOf<String, Pair<ViaductSchema.ObjectField, EngineOutputData?>>()
    selections.collect(type).byResponseKey().forEach { (responseKey, selection) ->
        val storedKey = selection.materializedObjectKey()
        val cell =
            try {
                reserveCell(storedKey)
            } catch (failure: NoSuchElementException) {
                throw NoSuchElementException(
                    "${failure.message} while materializing resolver input for $reader " +
                        "at result path $resultPath",
                    failure,
                )
            }
        cycleChecker.cycleCheck(reader, cell)
        val selectedValue: EngineOutputData? =
            cell
                .reserveValue()
                .await()
                .materializeSelectedValue(
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

// Awaits every argument binding but preserves the selection's symbolic OER-cell identity.
context(operation: OperationContext)
private suspend fun ObjectMaterializeSelection.materializedObjectKey(
): ObjectEngineResult.ObjectKey {
    key.fetchGroundedArguments()
    return key
}

// Recursively materializes one selected engine result while preserving null, error, and list shape.
context(operation: OperationContext, cycleChecker: CycleCheckState)
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
