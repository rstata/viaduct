package semantics.resolver26

import model.ObjectEngineResult

import model.Assumptions
import model.Schema
import model.Selection
import model.SelectionForest
import model.containsErrorValue
import model.flatMapToSelectionForest
import model.objectKey
import model.selectionForestOf

// Returns ground output demand, crossing open resolver boundaries without binding their arguments.
context(world: Assumptions)
internal fun SelectionForest.successorDemand(): SelectionForest =
    successorDemand(mutableMapOf())

// Retains requested ground boundaries and adds each active boundary's fixed passive OF demand.
context(world: Assumptions)
private fun SelectionForest.successorDemand(
    passiveDemandByResolverField: MutableMap<Schema.ObjectField, SelectionForest>,
): SelectionForest =
    flatMap { selection ->
        selection.possibleTypes.flatMapToSelectionForest { possibleType ->
            val objectKey: ObjectEngineResult.ObjectKey = selection.objectKey(possibleType)
            val requestedDemand: SelectionForest =
                if (
                    objectKey.field in world.resolverRegistry &&
                    objectKey !is ObjectEngineResult.GroundKey
                ) {
                    selectionForestOf()
                } else {
                    check(
                        objectKey is ObjectEngineResult.GroundKey ||
                            objectKey.field in world.resolverRegistry,
                    ) {
                        "Resolver26 found open arguments on passive key $objectKey"
                    }
                    selectionForestOf(
                        Selection.of(
                            key = objectKey,
                            possibleTypes = setOf(possibleType),
                            subselections =
                                selection.subselections.successorDemand(
                                    passiveDemandByResolverField,
                                ),
                        ),
                    )
                }
            val successorInputDemand: SelectionForest =
                when {
                    objectKey.arguments.containsErrorValue() ->
                        selectionForestOf()

                    objectKey.field in world.resolverRegistry ->
                        objectKey.field.fixedPassivePredecessorDemand(
                            passiveDemandByResolverField,
                        )

                    else -> selectionForestOf()
                }
            requestedDemand + successorInputDemand
        }
    }

// Memoizes passive demand reachable from one resolver OF before another resolver boundary.
context(world: Assumptions)
private fun Schema.ObjectField.fixedPassivePredecessorDemand(
    passiveDemandByResolverField: MutableMap<Schema.ObjectField, SelectionForest>,
): SelectionForest =
    passiveDemandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .passivePredecessorDemand(passiveDemandByResolverField)
            .also { demand -> passiveDemandByResolverField[this] = demand }

// Retains passive OF selections and replaces active selections with their own passive OF demand.
context(world: Assumptions)
private fun SelectionForest.passivePredecessorDemand(
    passiveDemandByResolverField: MutableMap<Schema.ObjectField, SelectionForest>,
): SelectionForest =
    flatMap { selection ->
        selection.possibleTypes.flatMapToSelectionForest { possibleType ->
            val objectKey: ObjectEngineResult.ObjectKey = selection.objectKey(possibleType)
            if (objectKey.field in world.resolverRegistry) {
                objectKey.field.fixedPassivePredecessorDemand(
                    passiveDemandByResolverField,
                )
            } else {
                check(objectKey is ObjectEngineResult.GroundKey) {
                    "Resolver26 found open arguments on passive key $objectKey"
                }
                selectionForestOf(
                    Selection.of(
                        key = objectKey,
                        possibleTypes = setOf(possibleType),
                        subselections =
                            selection.subselections.passivePredecessorDemand(
                                passiveDemandByResolverField,
                            ),
                    ),
                )
            }
        }
    }
