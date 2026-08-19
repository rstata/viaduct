package semantics

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectMaterializeSelection
import model.PathComponent
import model.Schema
import model.Selection
import model.Stamp
import model.TypeExpr
import model.Value
import model.fetchBindings
import model.localizeTopLevelSelectionStamps
import model.selectionForestOf
import model.toValue

/**
 * Materializes the object value selected by [selections] from this result.
 *
 * [reader] is the exact root-relative coordinate of the resolver consuming the materialized value.
 *
 * This operation is defined when this result contains every value promise selected by [selections] and every selection applicable at an object visited by this operation contains no [Value.Variable] in its key arguments.
 */
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal suspend fun ObjectEngineResult.materialize(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
): Value.Object {
    return materializeSelectedObjectValue(
        selections = selections,
        reader = reader,
        resultPath = reader.dropLast(1),
        selectionPath = emptyList(),
    )
}

// Materializes a selection forest rooted at one exact OER path.
context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun ObjectEngineResult.materializeSelectedObjectValue(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
    selectionPath: List<PathComponent>,
): Value.Object {
    val selectedValues =
        linkedMapOf<String, Pair<model.Schema.ObjectField, Value.Output?>>()
    selections.collect(type).byResponseKey().forEach { (responseKey, selection) ->
        val storedKey = selection.materializedGroundKey(selectionPath)
        val cell = getCell(storedKey)
        val promise = cell.getValue()
        runtimeSupport.cycleCheck(reader, cell)
        val selectedValue =
            promise
                .await()
                .materializeEngineResultValue(
                    expectedType = storedKey.field.typeExpr,
                    selections = selection.subselections,
                    reader = reader,
                    resultPath = resultPath + storedKey,
                )
        selectedValues[responseKey] = storedKey.field to selectedValue
    }
    return Value.Object.of(
        type,
        selectedValues.map { (key, fieldAndValue) ->
            Value.Object.FieldValue.of(key, fieldAndValue.first, fieldAndValue.second)
        },
    )
}

context(world: Assumptions)
internal suspend fun ObjectMaterializeSelection.materializedGroundKey(
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

// Recursively materializes one selected result while retaining its exact stored path.
context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun EngineResult?.materializeEngineResultValue(
    expectedType: TypeExpr<Schema.OutputType>,
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): Value.Output? =
    when (this) {
        null -> null
        ErrorEngineResult -> Value.Error
        is ObjectEngineResult ->
            materializeSelectedObjectValue(
                selections = selections,
                reader = reader,
                resultPath = resultPath,
                selectionPath = resultPath,
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
        else -> toValue(expectedType.baseType as Schema.SimpleType)
    }

// Materializes each list element at a path containing its concrete list index.
context(world: Assumptions, runtimeSupport: RuntimeSupport)
private suspend fun ListEngineResult.materializeValues(
    selections: MaterializeSelectionForest,
    reader: List<PathComponent>,
    resultPath: List<PathComponent>,
): kotlin.collections.List<Value.Output?> {
    val materialized = mutableListOf<Value.Output?>()
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
