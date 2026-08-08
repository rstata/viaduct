package semantics

import model.Assumptions
import model.EngineResult
import model.MissingFieldException
import model.ObjectSelectionForest
import model.PathComponent
import model.Schema
import model.SelectionForest
import model.Value
import model.instantiateBindings
import model.merge

/**
 * One allocated object occurrence whose cells are written at most once before [freeze].
 *
 * Object-valued cells refer to other partial OERs, so writing a parent cell does not require its
 * descendants to have completed resolution.
 */
internal class PartialOER(
    val path: List<PathComponent>,
    val source: Value.Object,
) {
    val type: Schema.ObjectType
        get() = source.type

    private val cells = mutableMapOf<Value.GroundKey, PartialCell>()

    val keys: Set<Value.GroundKey>
        get() = cells.keys

    fun write(
        key: Value.GroundKey,
        cell: PartialCell,
    ) {
        require(key.field.containingType == type) {
            "Partial ${type.typeName} result contains a field owned by another type"
        }
        check(key !in cells) {
            "OER cell $key at ${path.ifEmpty { listOf("<root>") }} was written more than once"
        }
        cells[key] = cell
    }

    /** @throws MissingFieldException when [key] is unwritten */
    fun fetch(key: Value.GroundKey): PartialCell =
        cells[key]
            ?: throw MissingFieldException(type.typeName, key.field.fieldName)

    fun freeze(): EngineResult.Object =
        EngineResult.Object.of(
            type = type,
            cells = cells.mapValues { (_, cell) -> cell.freeze() },
        )
}

internal data class PartialCell(
    val value: PartialValue?,
    val check: Value.Boolean = Value.Boolean.of(true),
) {
    fun freeze(): EngineResult.Cell =
        EngineResult.Cell.of(
            value = value.freeze(),
            check = check,
        )

    companion object {
        val Error =
            PartialCell(
                value = PartialValue.Terminal(Value.Error),
                check = Value.Error,
            )
    }
}

internal sealed interface PartialValue {
    data class Terminal(
        val value: Value.Simple,
    ) : PartialValue

    data class ObjectReference(
        val oer: PartialOER,
    ) : PartialValue

    data class ListValue(
        val typeExpr: model.TypeExpr<Schema.OutputType>,
        val cells: List<PartialCell>,
    ) : PartialValue
}

internal fun PartialValue?.freeze(): EngineResult? =
    when (this) {
        null -> null
        is PartialValue.Terminal -> value
        is PartialValue.ObjectReference -> oer.freeze()
        is PartialValue.ListValue ->
            EngineResult.List.of(
                typeExpr = typeExpr,
                cells = cells.map(PartialCell::freeze),
            )
    }

context(world: Assumptions)
internal fun PartialOER.materialize(selections: ObjectSelectionForest): Value.Object {
    require(type == selections.type) {
        "Selection type ${selections.type.typeName} does not match result type ${type.typeName}"
    }
    return materializeSelectedObjectValue(selections)
}

context(world: Assumptions)
private fun PartialOER.materializeSelectedObjectValue(
    selections: SelectionForest,
): Value.Object =
    materializeSelectedObjectValue(selections.merge(type).instantiateBindings())

context(world: Assumptions)
private fun PartialOER.materializeSelectedObjectValue(
    selections: ObjectSelectionForest,
): Value.Object =
    Value.Object.of(
        type = type,
        fields =
            selections
                .byGroundKey()
                .mapValues { (key, selection) ->
                    fetch(key).value.materializePartialValue(selection.subselections)
                },
    )

context(world: Assumptions)
private fun PartialValue?.materializePartialValue(
    selections: SelectionForest,
): Value.Output? =
    when (this) {
        null -> null
        is PartialValue.Terminal -> value
        is PartialValue.ObjectReference -> oer.materializeSelectedObjectValue(selections)
        is PartialValue.ListValue ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values =
                    cells.map { cell ->
                        cell.value.materializePartialValue(selections)
                    },
            )
    }
