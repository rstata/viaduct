package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult

import model.Assumptions
import model.Selection
import model.SelectionForest
import model.containsErrorValue
import model.flatMapToSelectionForest
import model.objectKey
import model.merge
import model.requireField
import model.selectionForestOf

// Returns ground output demand, crossing open resolver boundaries without binding their arguments.
context(world: Assumptions)
internal fun SelectionForest.successorDemand(): SelectionForest =
    liftParentDemand()
        .successorDemandWithMemo(mutableMapOf())
        .liftParentDemand()

// Returns only construction demand induced by parent selections in requested descendants and in
// the fixed inputs of resolver boundaries reached from those descendants.
context(world: Assumptions)
internal fun SelectionForest.inputParentDemand(): SelectionForest =
    analyzeInputParentDemand(mutableMapOf()).localDemand

context(world: Assumptions)
private fun SelectionForest.analyzeInputParentDemand(
    parentDemandByResolverField: MutableMap<ViaductSchema.ObjectField, InputParentDemandAnalysis>,
): InputParentDemandAnalysis =
    foldInputParentDemand { selection ->
        val parentFields =
            selection.possibleTypes.mapNotNullTo(linkedSetOf()) { possibleType ->
                (selection.objectKey(possibleType) as? ObjectEngineResult.ParentKey)?.field
            }
        if (parentFields.isNotEmpty()) {
            InputParentDemandAnalysis(
                parentRequests =
                    parentFields.map { parentField ->
                        ParentInputRequest(parentField, selection.subselections)
                    },
            )
        } else {
            val nested =
                selection.subselections.analyzeInputParentDemand(parentDemandByResolverField)
            var localDemand =
                if (nested.localDemand.isEmpty()) {
                    selectionForestOf()
                } else {
                    selectionForestOf(
                        Selection.of(
                            key = selection.key,
                            possibleTypes = selection.possibleTypes,
                            subselections = nested.localDemand,
                        ),
                    )
                }
            val parentRequests = mutableListOf<ParentInputRequest>()
            nested.parentRequests.forEach { request ->
                val matchesProducer =
                    selection.possibleTypes.any { possibleType ->
                        val producer = possibleType.requireField(selection.key.field.name)
                        world.parentFieldRelations[request.parentField] == producer
                    }
                if (matchesProducer) {
                    val ancestor =
                        request.demand.analyzeInputParentDemand(parentDemandByResolverField)
                    localDemand += request.demand + ancestor.localDemand
                    parentRequests += ancestor.parentRequests
                }
            }
            selection.possibleTypes.forEach { possibleType ->
                val field = selection.objectKey(possibleType).field
                if (field in world.resolverRegistry) {
                    val resolverInput =
                        field.fixedInputParentDemand(parentDemandByResolverField)
                    localDemand += resolverInput.localDemand
                    parentRequests += resolverInput.parentRequests
                }
            }
            InputParentDemandAnalysis(localDemand, parentRequests)
        }
    }

context(world: Assumptions)
private fun ViaductSchema.ObjectField.fixedInputParentDemand(
    parentDemandByResolverField: MutableMap<ViaductSchema.ObjectField, InputParentDemandAnalysis>,
): InputParentDemandAnalysis =
    parentDemandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .analyzeInputParentDemand(parentDemandByResolverField)
            .also { demand -> parentDemandByResolverField[this] = demand }

private data class ParentInputRequest(
    val parentField: ViaductSchema.ObjectField,
    val demand: SelectionForest,
)

private data class InputParentDemandAnalysis(
    val localDemand: SelectionForest = selectionForestOf(),
    val parentRequests: List<ParentInputRequest> = emptyList(),
) {
    operator fun plus(other: InputParentDemandAnalysis): InputParentDemandAnalysis =
        InputParentDemandAnalysis(
            localDemand = localDemand + other.localDemand,
            parentRequests = parentRequests + other.parentRequests,
        )
}

private fun SelectionForest.foldInputParentDemand(
    transform: (Selection) -> InputParentDemandAnalysis,
): InputParentDemandAnalysis {
    var result = InputParentDemandAnalysis()
    forEach { selection -> result += transform(selection) }
    return result
}

// Conservatively transposes parent-selected demand to each containing producer occurrence.
context(world: Assumptions)
private fun SelectionForest.liftParentDemand(): SelectionForest =
    flatMap { selection ->
        val nested = selection.subselections.liftParentDemand()
        val requested =
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = nested,
            )
        val lifted = selection.liftedParentDemand(nested)
        selectionForestOf(requested) + lifted
    }

context(world: Assumptions)
private fun Selection.liftedParentDemand(
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
context(world: Assumptions)
private fun SelectionForest.successorDemandWithMemo(
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
                            subselections =
                                selection.subselections.successorDemandWithMemo(
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
private fun ViaductSchema.ObjectField.fixedPassivePredecessorDemand(
    passiveDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
): SelectionForest =
    passiveDemandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .passivePredecessorDemand(passiveDemandByResolverField)
            .also { demand -> passiveDemandByResolverField[this] = demand }

// Retains fields that may be passive based on presence and expands their standard passive demand.
context(world: Assumptions)
private fun SelectionForest.passivePredecessorDemand(
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
                                subselections =
                                    selection.subselections.successorDemandWithMemo(
                                        passiveDemandByResolverField,
                                    ),
                            ),
                        )
                    } else {
                        selectionForestOf()
                    }
                potentiallyPassiveSelection +
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
