package model.testing

import model.Fragment
import model.Selection
import model.SelectionForest
import model.Value
import model.selectionForestOf

internal fun Fragment.mapVariables(
    transform: (Value.Variable) -> Value.Variable,
): Fragment =
    Fragment.of(
        nominalType = nominalType,
        subselections = subselections.mapVariables(transform),
    )

internal fun List<Value.Key>.mapVariables(
    transform: (Value.Variable) -> Value.Variable,
): List<Value.Key> =
    map { key ->
        Value.Key.of(
            field = key.field,
            arguments =
                key.arguments.fieldValues.mapValues { (_, value) ->
                    value.mapVariables(transform)
                },
        )
    }

private fun SelectionForest.mapVariables(
    transform: (Value.Variable) -> Value.Variable,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    Value.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.fieldValues.mapValues { (_, value) ->
                                value.mapVariables(transform)
                            },
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.mapVariables(transform),
            ),
        )
    }

private fun Value.Input?.mapVariables(
    transform: (Value.Variable) -> Value.Variable,
): Value.Input? =
    when {
        this == null || this == Value.Error -> this
        this is Value.Variable -> transform(this)
        this is Value.InputList ->
            Value.InputList.of(
                typeExpr = typeExpr,
                values = values.map { it.mapVariables(transform) },
            )
        this is Value.InputObject ->
            Value.InputObject.of(
                type = type,
                fields = fieldValues.mapValues { (_, value) -> value.mapVariables(transform) },
            )
        else -> this
    }
