package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult

import model.Assumptions
import model.Selection
import model.SelectionForest
import model.containsErrorValue
import model.flatMapToSelectionForest
import model.guardedBy
import model.objectKey
import model.merge
import model.requireField
import model.selectionForestOf

// Returns ground output demand, crossing open resolver boundaries without binding their arguments.
internal fun SelectionForest.successorDemand(world: Assumptions): SelectionForest =
    liftParentDemand(world)
        .successorDemandWithMemo(world, mutableMapOf())
        .liftParentDemand(world)

// Conservatively transposes parent-selected demand to each containing producer occurrence.
private fun SelectionForest.liftParentDemand(world: Assumptions): SelectionForest =
    flatMap { selection ->
        val nested = selection.subselections.liftParentDemand(world)
        val requested =
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = nested,
                inclusionCondition = selection.inclusionCondition,
            )
        val lifted =
            selection
                .liftedParentDemand(world, nested)
                .guardedBy(selection.inclusionCondition)
        selectionForestOf(requested) + lifted
    }

private fun Selection.liftedParentDemand(
    world: Assumptions,
    nestedDemand: SelectionForest,
): SelectionForest =
    possibleTypes.flatMapToSelectionForest { possibleType ->
        val producer = possibleType.requireField(key.field.name)
        val childType = producer.type.baseTypeDef as? ViaductSchema.Object
            ?: return@flatMapToSelectionForest selectionForestOf()
        nestedDemand
            .merge(childType)
            .byKey()
            .values
            .filter { childSelection ->
                val parentKey = childSelection.key as? ObjectEngineResult.ParentKey
                parentKey != null &&
                    world.parentFieldRelations[parentKey.field] == producer
            }
            .fold(selectionForestOf()) { demand, parentSelection ->
                demand + parentSelection.subselections
            }
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
