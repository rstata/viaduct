package semantics.shared

import model.Assumptions
import model.ObjectEngineResult
import model.Selection
import model.SelectionForest
import model.InclusionCondition
import model.guardedBy
import model.objectKey
import model.requireField
import model.selectionForestOf
import viaduct.graphql.schema.ViaductSchema

/**
 * Returns construction demand induced by parent selections in requested descendants and in the
 * fixed inputs of resolver boundaries reached from those descendants.
 */
internal fun SelectionForest.inputParentDemand(world: Assumptions): SelectionForest =
    if (world.parentFieldRelations.isEmpty()) {
        selectionForestOf()
    } else {
        computeDemandFromParentFields(world, mutableMapOf()).localDemand
    }

private fun SelectionForest.computeDemandFromParentFields(
    world: Assumptions,
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
                        ParentInputRequest(
                            parentField,
                            selection.subselections.guardedBy(selection.inclusionCondition),
                        )
                    },
            )
        } else {
            val nested =
                selection.subselections.computeDemandFromParentFields(world, parentDemandByResolverField)
            var localDemand =
                if (nested.localDemand.isEmpty()) {
                    selectionForestOf()
                } else {
                    selectionForestOf(
                        Selection.of(
                            key = selection.key,
                            possibleTypes = selection.possibleTypes,
                            subselections = nested.localDemand,
                            inclusionCondition = selection.inclusionCondition,
                        ),
                    )
                }
            val parentRequests = mutableListOf<ParentInputRequest>()
            nested.parentRequests.forEach { unguardedRequest ->
                val request =
                    ParentInputRequest(
                        parentField = unguardedRequest.parentField,
                        demand =
                            unguardedRequest.demand.guardedBy(
                                selection.inclusionCondition,
                            ),
                    )
                val matchesProducer =
                    selection.possibleTypes.any { possibleType ->
                        val producer = possibleType.requireField(selection.key.field.name)
                        world.parentFieldRelations[request.parentField] == producer
                    }
                if (matchesProducer) {
                    val ancestor =
                        request.demand.computeDemandFromParentFields(world, parentDemandByResolverField)
                    localDemand += request.demand + ancestor.localDemand
                    parentRequests += ancestor.parentRequests
                }
            }
            selection.possibleTypes.forEach { possibleType ->
                val field = selection.objectKey(possibleType).field
                if (field in world.resolverRegistry) {
                    val resolverInput =
                        field.fixedInputParentDemand(world, parentDemandByResolverField)
                    val guardedResolverInput =
                        resolverInput.guardedBy(selection.inclusionCondition)
                    localDemand += guardedResolverInput.localDemand
                    parentRequests += guardedResolverInput.parentRequests
                }
            }
            InputParentDemandAnalysis(localDemand, parentRequests)
        }
    }

private fun ViaductSchema.ObjectField.fixedInputParentDemand(
    world: Assumptions,
    parentDemandByResolverField: MutableMap<ViaductSchema.ObjectField, InputParentDemandAnalysis>,
): InputParentDemandAnalysis =
    parentDemandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .withoutInclusionConditions()
            .computeDemandFromParentFields(world, parentDemandByResolverField)
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

private fun InputParentDemandAnalysis.guardedBy(
    condition: InclusionCondition,
): InputParentDemandAnalysis =
    InputParentDemandAnalysis(
        localDemand = localDemand.guardedBy(condition),
        parentRequests =
            parentRequests.map { request ->
                ParentInputRequest(
                    parentField = request.parentField,
                    demand = request.demand.guardedBy(condition),
                )
            },
    )

private fun SelectionForest.foldInputParentDemand(
    transform: (Selection) -> InputParentDemandAnalysis,
): InputParentDemandAnalysis {
    var result = InputParentDemandAnalysis()
    forEach { selection -> result += transform(selection) }
    return result
}

/** Fixed descendant demand is lifted before occurrence-local condition bindings can exist. */
private fun SelectionForest.withoutInclusionConditions(): SelectionForest =
    flatMap { selection ->
        if (selection.inclusionCondition === InclusionCondition.Never) {
            return@flatMap selectionForestOf()
        }
        selectionForestOf(
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                inclusionCondition = InclusionCondition.Always,
                subselections = selection.subselections.withoutInclusionConditions(),
            ),
        )
    }
