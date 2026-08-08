package model.testing

import model.Fragment
import model.Selection
import model.SelectionForest
import model.Value
import model.mapVariableTemplates
import model.selectionForestOf

internal fun Fragment.mapVariables(
    transform: (Value.Variable.Template) -> Value.Variable.Template,
): Fragment =
    Fragment.of(
        nominalType = nominalType,
        subselections = subselections.mapVariables(transform),
    )

internal fun List<Value.Key>.mapVariables(
    transform: (Value.Variable.Template) -> Value.Variable.Template,
): List<Value.Key> =
    map { key ->
        Value.Key.of(
            field = key.field,
            arguments = key.arguments.mapVariableTemplates(transform),
        )
    }

private fun SelectionForest.mapVariables(
    transform: (Value.Variable.Template) -> Value.Variable.Template,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    Value.Key.of(
                        field = selection.key.field,
                        arguments = selection.key.arguments.mapVariableTemplates(transform),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.mapVariables(transform),
            ),
        )
    }
