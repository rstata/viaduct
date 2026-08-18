package model.testing

import model.ObjectEngineResult

import model.Fragment
import model.MaterializeSelection
import model.MaterializeSelectionForest
import model.Selection
import model.SelectionForest
import model.Value
import model.materializeSelectionForestOf
import model.mapVariableTemplates
import model.selectionForestOf

internal fun Fragment.mapVariables(
    transform: (Value.Variable) -> Value.Variable,
): Fragment =
    Fragment.of(
        nominalType = nominalType,
        materializeSelections = materializeSelections.mapVariables(transform),
    )

internal fun List<ObjectEngineResult.Key>.mapVariables(
    transform: (Value.Variable) -> Value.Variable,
): List<ObjectEngineResult.Key> =
    map { key ->
        ObjectEngineResult.Key.of(
            field = key.field,
            arguments = key.arguments.mapVariableTemplates(key.field.arguments, transform),
        )
    }

private fun SelectionForest.mapVariables(
    transform: (Value.Variable) -> Value.Variable,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.mapVariableTemplates(
                                selection.key.field.arguments,
                                transform,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.mapVariables(transform),
            ),
        )
    }

private fun MaterializeSelectionForest.mapVariables(
    transform: (Value.Variable) -> Value.Variable,
): MaterializeSelectionForest =
    flatMap { selection ->
        materializeSelectionForestOf(
            MaterializeSelection.of(
                responseKey = selection.responseKey,
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.mapVariableTemplates(
                                selection.key.field.arguments,
                                transform,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.mapVariables(transform),
            ),
        )
    }
