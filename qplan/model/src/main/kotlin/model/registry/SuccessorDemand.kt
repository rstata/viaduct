package model.registry

import model.Assumptions
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.objectKey
import model.selectionForestOf

/**
 * Extends this output demand with every encountered successor resolver's predecessor demand.
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
        val predecessorDemand =
            selection.possibleTypes.fold(selectionForestOf()) { demand, possibleType ->
                val key = selection.objectKey(possibleType)
                if (
                    key.arguments.containsErrorValue() ||
                    key.field !in world.resolverRegistry
                ) {
                    demand
                } else {
                    demand +
                        world.resolverRegistry
                            .resolver(key.field)
                            .predecessorDemand(key.arguments)
                            .subselections
                }
            }
        selectionForestOf(rootedSelection) + predecessorDemand
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
