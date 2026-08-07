package model.registry

import model.Assumptions
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.objectKey
import model.selectionForestOf
import model.substituteBindings

/**
 * Extends this output demand with every encountered successor resolver's transitive input demand.
 *
 * Each predecessor demand is rooted at its successor occurrence's containing object. Recursing
 * through subselections preserves the selection-occurrence nesting path and concrete-type guards.
 */
context(world: Assumptions)
fun SelectionForest.successorDemand(): SelectionForest =
    flatMap { selection ->
        val nestedDemand = selection.subselections.successorDemand()
        val rootedSelection =
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = nestedDemand,
            )
        val resolverInputDemand =
            selection.possibleTypes.fold(selectionForestOf()) { demand, possibleType ->
                val key = selection.objectKey(possibleType).substituteBindings()
                if (
                    key.arguments.containsErrorValue() ||
                    key.field !in world.resolverRegistry
                ) {
                    demand
                } else {
                    demand +
                        world.resolverRegistry
                            .resolver(key.field)
                            .objectFragmentWithFromArguments(key.arguments)
                            .successorDemand()
                }
            }
        selectionForestOf(rootedSelection) + resolverInputDemand
    }

private fun FieldResolver.objectFragmentWithFromArguments(
    arguments: Value.Arguments,
): SelectionForest {
    val bindings =
        variables.mapNotNull { (variable, definition) ->
            (definition as? VariableDefinition.FromArgument)?.let {
                variable to
                    arguments.fieldValues.getValue(
                        definition.argument.argumentName,
                    )
            }
        }.toMap()
    return objectFragment(arguments).substitute(bindings)
}

private fun SelectionForest.substitute(
    bindings: Map<Value.Variable.Template, Value.Input?>,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    Value.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.substitute(
                                field = selection.key.field,
                                bindings = bindings,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.substitute(bindings),
            ),
        )
    }

private fun Value.Arguments.substitute(
    field: Schema.OutputField,
    bindings: Map<Value.Variable.Template, Value.Input?>,
): Value.Arguments =
    Value.Arguments.of(
        field = field,
        fields =
            fieldValues.mapValues { (_, value) ->
                value.substitute(bindings)
            },
    )

private fun Value.Input?.substitute(
    bindings: Map<Value.Variable.Template, Value.Input?>,
): Value.Input? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Variable.Template ->
            if (this in bindings) bindings[this] else this
        is Value.InputList ->
            Value.InputList.of(
                typeExpr = typeExpr,
                values = values.map { value -> value.substitute(bindings) },
            )
        is Value.InputObject ->
            Value.InputObject.of(
                type = type,
                fields =
                    fieldValues.mapValues { (_, value) ->
                        value.substitute(bindings)
                    },
            )
        else -> this
    }

private fun Value.Arguments.containsErrorValue(): Boolean =
    fieldValues.values.any { value -> value.containsErrorValue() }

private fun Value.Input?.containsErrorValue(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.InputList -> values.any { value -> value.containsErrorValue() }
        is Value.InputObject ->
            fieldValues.values.any { value -> value.containsErrorValue() }
        else -> false
    }
