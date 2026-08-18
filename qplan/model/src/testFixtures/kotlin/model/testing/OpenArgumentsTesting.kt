package model.testing

import model.ObjectEngineResult

import model.MaterializeSelection
import model.OpenArguments
import model.Selection
import model.Value
import model.fieldExpressions

/** Replaces selected argument expressions with [Value.Error] during fixture composition. */
fun Selection.withErrorArguments(argumentNames: Set<String>): Selection =
    Selection.of(
        key =
            ObjectEngineResult.Key.of(
                field = key.field,
                arguments =
                    OpenArguments.of(
                        key.field,
                        key.arguments
                            .fieldExpressions()
                            .mapValues { (name, value) ->
                                if (name in argumentNames) Value.Error else value
                            },
                    ),
            ),
        possibleTypes = possibleTypes,
        subselections = subselections,
    )

/** Replaces selected argument expressions with [Value.Error] during fixture composition. */
fun MaterializeSelection.withErrorArguments(
    argumentNames: Set<String>,
): MaterializeSelection =
    MaterializeSelection.of(
        responseKey = responseKey,
        key =
            ObjectEngineResult.Key.of(
                field = key.field,
                arguments =
                    OpenArguments.of(
                        key.field,
                        key.arguments
                            .fieldExpressions()
                            .mapValues { (name, value) ->
                                if (name in argumentNames) Value.Error else value
                            },
                    ),
            ),
        possibleTypes = possibleTypes,
        subselections = subselections,
    )
