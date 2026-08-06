package semantics.resolver04

import model.Assumptions
import model.EngineResult
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.objectKey
import model.selectionForestOf
import model.union
import semantics.variables
import java.util.IdentityHashMap

context(world: Assumptions)
internal fun SelectionForest.coverageFor(key: Value.ObjectKey): SelectionForest =
    flatMap { selection -> selection.coverageFor(key) }

context(world: Assumptions)
private fun Selection.coverageFor(key: Value.ObjectKey): SelectionForest {
    val objectType = key.field.containingType
    if (objectType !in possibleTypes) return selectionForestOf()
    val concreteKey = objectKey(objectType)
    if (concreteKey.field != key.field) return selectionForestOf()
    if (concreteKey.arguments == key.arguments) {
        return selectionForestOf(withoutVariableSubselections())
    }
    if (this.key.arguments.variables().isEmpty()) {
        return selectionForestOf()
    }
    return selectionForestOf(
        Selection.of(
            key =
                Value.Key.of(
                    field = this.key.field,
                    arguments = key.arguments.fieldValues,
                ),
            possibleTypes = possibleTypes,
            subselections = subselections.withoutVariableKeys(),
        ),
    )
}

private fun Selection.withoutVariableSubselections(): Selection =
    Selection.of(
        key = key,
        possibleTypes = possibleTypes,
        subselections = subselections.withoutVariableKeys(),
    )

/**
 * Retains every selection whose own key is concrete, recursively omitting symbolic descendants.
 */
private fun SelectionForest.withoutVariableKeys(): SelectionForest =
    flatMap { selection ->
        if (selection.key.arguments.variables().isNotEmpty()) {
            selectionForestOf()
        } else {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = selection.subselections.withoutVariableKeys(),
                ),
            )
        }
    }

/** Returns [key] with newly required descendants added without reapplying its producer. */
context(world: Assumptions, sources: ResolutionSources)
internal fun EngineResult.Object.resolveExistingKey(
    key: Value.ObjectKey,
    fieldSelections: SelectionForest,
    coverage: SelectionForest,
): EngineResult.Object {
    val existing = fetch(key)
    val selections = fieldSelections.flatMap { selection -> selection.subselections }
    val available =
        sources
            .output(existing.value)
            .availableDemand(coverage.flatMap { selection -> selection.subselections })
    return EngineResult.Object.of(
        type = type,
        cells =
            mapOf(
                key to
                    EngineResult.Cell.of(
                        value = existing.value.resolveAdditional(selections, available),
                        check = existing.check,
                    ),
            ),
    )
}

context(world: Assumptions, sources: ResolutionSources)
private fun EngineResult?.resolveAdditional(
    selections: SelectionForest,
    envelope: SelectionForest,
): EngineResult? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> {
            require(selections.isEmpty() && envelope.isEmpty())
            this
        }

        is EngineResult.Object ->
            (sources.source(this) ?: asValueObject()).resolve(selections, this, envelope)

        is EngineResult.List ->
            EngineResult.List.of(
                typeExpr = typeExpr,
                cells =
                    map { cell ->
                        EngineResult.Cell.of(
                            value = cell.value.resolveAdditional(selections, envelope),
                            check = cell.check,
                        )
                    },
            )
    }

internal class ResolutionSources {
    private val objects = IdentityHashMap<EngineResult.Object, Value.Object>()

    fun source(result: EngineResult.Object): Value.Object? = objects[result]

    fun output(result: EngineResult?): Value.Output? =
        when (result) {
            null -> null
            Value.Error -> Value.Error
            is Value.Simple -> result
            is EngineResult.Object -> source(result) ?: result.asValueObject()
            is EngineResult.List ->
                Value.OutputList.of(
                    typeExpr = result.typeExpr,
                    values = result.map { cell -> output(cell.value) },
                )
        }

    fun remember(
        result: EngineResult?,
        source: Value.Output?,
    ) {
        when {
            result is EngineResult.Object && source is Value.Object -> {
                objects[result] = source
                result.cells.forEach { (key, cell) ->
                    if (key in source.fieldValues) {
                        remember(cell.value, source.fieldValues.getValue(key))
                    }
                }
            }

            result is EngineResult.List && source is Value.OutputList &&
                result.size == source.values.size ->
                result.indices.forEach { index ->
                    remember(result[index].value, source.values[index])
                }
        }
    }

    fun union(
        left: EngineResult.Object,
        right: EngineResult.Object,
    ): EngineResult.Object =
        left.union(right).also { result -> inherit(result, listOf(left, right)) }

    private fun inherit(
        result: EngineResult?,
        antecedents: List<EngineResult?>,
    ) {
        when (result) {
            is EngineResult.Object -> {
                val objectAntecedents = antecedents.filterIsInstance<EngineResult.Object>()
                objectAntecedents
                    .firstNotNullOfOrNull(::source)
                    ?.let { source -> objects[result] = source }
                result.cells.forEach { (key, cell) ->
                    inherit(
                        cell.value,
                        objectAntecedents
                            .mapNotNull { antecedent -> antecedent.cells[key]?.value },
                    )
                }
            }

            is EngineResult.List ->
                result.indices.forEach { index ->
                    inherit(
                        result[index].value,
                        antecedents
                            .filterIsInstance<EngineResult.List>()
                            .filter { antecedent -> antecedent.size == result.size }
                            .map { antecedent -> antecedent[index].value },
                    )
                }

            else -> Unit
        }
    }
}

private fun EngineResult.Object.asValueObject(): Value.Object =
    Value.Object.of(
        type = type,
        fields = cells.mapValues { (_, cell) -> cell.value.asOutputValue() },
    )

private fun EngineResult?.asOutputValue(): Value.Output? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.Object -> asValueObject()
        is EngineResult.List ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values = map { cell -> cell.value.asOutputValue() },
            )
    }
