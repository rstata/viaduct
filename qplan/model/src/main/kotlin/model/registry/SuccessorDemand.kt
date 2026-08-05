package model.registry

import model.Assumptions
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.selectionForestOf

/**
 * Extends this output demand with every encountered successor resolver's predecessor demand.
 *
 * Each predecessor demand is rooted at its successor occurrence's containing object. Recursing
 * through subselections preserves the occurrence path and concrete-type guards.
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
        val predecessorDemand =
            selection.possibleTypes.fold(selectionForestOf()) { demand, possibleType ->
                val key = selection.concreteObjectKey(possibleType)
                if (
                    key.arguments.containsErrorValue() ||
                    key.field !in world.executorRegistry
                ) {
                    demand
                } else {
                    demand +
                        world.executorRegistry
                            .resolver(key.field)
                            .predecessorDemand(key.arguments)
                            .subselections
                }
            }
        selectionForestOf(rootedSelection) + predecessorDemand
    }

context(world: Assumptions)
private fun Selection.concreteObjectKey(type: Schema.ObjectType): Value.Key =
    Value.Key.of(
        field = world.schema.field(type.typeName, key.field.fieldName),
        arguments = key.arguments.fieldValues,
    )

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
