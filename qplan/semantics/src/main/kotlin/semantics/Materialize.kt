package semantics

import viaduct.graphql.schema.ViaductSchema

import model.Arguments

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
import model.ObjectMaterializeSelection
import model.PathComponent
import model.Selection
import model.Stamp
import model.fetchBindings
import model.localizeTopLevelSelectionStamps
import model.materializedEngineObjectDataOf
import model.selectionForestOf
import model.toEngineOutputData
import viaduct.engine.api.EngineObjectData

/**
 * Materializes the object value selected by [selections] from this result.
 *
 * [reader] is the exact root-relative coordinate of the resolver consuming the materialized value.
 *
 * This operation is defined when this result contains every value promise selected by [selections] and every selection applicable at an object visited by this operation contains no [Arguments.Variable] in its key arguments.
 */
context(world: Assumptions, resolverSupport: ResolverSupport)
internal suspend fun ObjectEngineResult.materialize(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
): EngineObjectData.Sync {
    return materializeSelectedObjectValue(
        selections = selections,
        reader = reader,
        resultPath = reader.dropLast(1),
        selectionPath = emptyList(),
    )
}

// Materializes a selection forest rooted at one exact OER path.
context(world: Assumptions, resolverSupport: ResolverSupport)
private suspend fun ObjectEngineResult.materializeSelectedObjectValue(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
    selectionPath: List<PathComponent>,
): EngineObjectData.Sync {
    val selectedValues =
        linkedMapOf<String, Pair<ViaductSchema.ObjectField, EngineOutputData?>>()
    selections.collect(type).byResponseKey().forEach { (responseKey, selection) ->
        val storedKey = selection.materializedGroundKey(selectionPath)
        val cell = getCell(storedKey)
        val promise = cell.getValue()
        resolverSupport.cycleCheck(reader, cell)
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

context(world: Assumptions)
internal suspend fun ObjectMaterializeSelection.materializedGroundKey(
    selectionPath: List<PathComponent>,
): ObjectEngineResult.GroundKey {
    val arguments = key.arguments.fetchBindings(key.field)
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
                possibleTypes = setOf(groundedKey.field.containingDef),
                subselections = selectionForestOf(),
            ),
        ).localizeTopLevelSelectionStamps(selectionPath)
        .single()
        .key
    return localizedKey as? ObjectEngineResult.GroundKey
        ?: error("Localized materialize key is not ground: $localizedKey")
}

// Recursively materializes one selected result while retaining its exact stored path.
context(world: Assumptions, resolverSupport: ResolverSupport)
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
                selectionPath = resultPath,
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
context(world: Assumptions, resolverSupport: ResolverSupport)
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
