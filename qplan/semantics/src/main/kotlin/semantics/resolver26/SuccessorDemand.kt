package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult

import model.Assumptions
import model.Selection
import model.SelectionForest
import model.containsErrorValue
import model.flatMapToSelectionForest
import model.objectKey
import model.selectionForestOf
import semantics.shared.liftParentSuccessorDemand

// Returns ground output demand, crossing open resolver boundaries without binding their arguments.
internal fun SelectionForest.successorDemand(world: Assumptions): SelectionForest {
    val initialDemand = this + liftParentSuccessorDemand(world)
    val expandedDemand = initialDemand.successorDemandWithMemo(world, mutableMapOf())
    return expandedDemand + expandedDemand.liftParentSuccessorDemand(world)
}

// Retains requested ground boundaries and adds each resolver-bearing boundary's fixed passive demand.
private fun SelectionForest.successorDemandWithMemo(
    world: Assumptions,
    passiveDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
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
                            inclusionCondition = selection.inclusionCondition,
                            subselections =
                                selection.subselections.successorDemandWithMemo(
                                    world,
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
                            world,
                            passiveDemandByResolverField,
                        )

                    else -> selectionForestOf()
                }
            requestedDemand + successorInputDemand
        }
    }

// Memoizes passive demand reachable from one resolver OF before another resolver boundary.
private fun ViaductSchema.ObjectField.fixedPassivePredecessorDemand(
    world: Assumptions,
    passiveDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
): SelectionForest =
    passiveDemandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .passivePredecessorDemand(world, passiveDemandByResolverField)
            .also { demand -> passiveDemandByResolverField[this] = demand }

// Retains fields that may be passive based on presence and expands their standard passive demand.
private fun SelectionForest.passivePredecessorDemand(
    world: Assumptions,
    passiveDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
): SelectionForest =
    flatMap { selection ->
        selection.possibleTypes.flatMapToSelectionForest { possibleType ->
            val objectKey: ObjectEngineResult.ObjectKey = selection.objectKey(possibleType)
            if (objectKey.field in world.resolverRegistry) {
                val potentiallyPassiveSelection =
                    if (objectKey.field.args.isEmpty()) {
                        selectionForestOf(
                            Selection.of(
                                key = objectKey,
                                possibleTypes = setOf(possibleType),
                                inclusionCondition = selection.inclusionCondition,
                                subselections =
                                    selection.subselections.successorDemandWithMemo(
                                        world,
                                        passiveDemandByResolverField,
                                    ),
                            ),
                        )
                    } else {
                        selectionForestOf()
                    }
                potentiallyPassiveSelection +
                    objectKey.field.fixedPassivePredecessorDemand(
                        world,
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
                        inclusionCondition = selection.inclusionCondition,
                        subselections =
                            selection.subselections.passivePredecessorDemand(
                                world,
                                passiveDemandByResolverField,
                            ),
                    ),
                )
            }
        }
    }
