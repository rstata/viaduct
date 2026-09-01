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
import model.ObjectMaterializeSelection
import model.Selection
import model.fetchGroundedArguments
import model.localizeTopLevelSelectionStamps
import model.outputType
import model.PathComponent
import model.materializedEngineObjectDataOf
import model.selectionForestOf
import model.toEngineOutputData
import semantics.ResolverSupport
import viaduct.engine.api.EngineObjectData

// Returns a resolver-visible input object collected by GraphQL response key.
context(world: Assumptions, resolverSupport: ResolverSupport)
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
context(world: Assumptions, resolverSupport: ResolverSupport)
private suspend fun ObjectEngineResult.materializeSelectedObject(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
    selectionPath: List<PathComponent>,
): EngineObjectData.Sync {
    val selectedValues =
        linkedMapOf<String, Pair<ViaductSchema.ObjectField, EngineOutputData?>>()
    selections.collect(type).byResponseKey().forEach { (responseKey, selection) ->
        val storedKey = selection.materializedObjectKey(selectionPath)
        val cell = reserveCell(storedKey)
        resolverSupport.cycleCheck(reader, cell)
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
context(world: Assumptions)
private suspend fun ObjectMaterializeSelection.materializedObjectKey(
    selectionPath: List<PathComponent>,
): ObjectEngineResult.ObjectKey {
    val storedKey =
        if (selectionPath.isEmpty()) {
            key
        } else {
            selectionForestOf(
                Selection.of(
                    key = key,
                    possibleTypes = setOf(key.field.containingDef),
                    subselections = selectionForestOf(),
                ),
            ).localizeTopLevelSelectionStamps(selectionPath)
                .single()
                .key as ObjectEngineResult.ObjectKey
        }
    storedKey.fetchGroundedArguments()
    return storedKey
}

// Recursively materializes one selected engine result while preserving null, error, and list shape.
context(world: Assumptions, resolverSupport: ResolverSupport)
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
