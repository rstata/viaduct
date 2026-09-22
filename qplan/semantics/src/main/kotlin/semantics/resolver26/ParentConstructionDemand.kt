package semantics.resolver26

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
 * Returns additional construction demand induced by parent selections in requested descendants
 * and in the fixed inputs of resolver boundaries reached from those descendants. The caller adds
 * this contribution to its original demand; the two may overlap.
 */
internal fun SelectionForest.liftParentConstructionDemand(world: Assumptions): SelectionForest =
    if (world.parentFieldRelations.isEmpty()) {
        selectionForestOf()
    } else {
        computeDemandFromParentFields(world, mutableMapOf()).localDemand
    }

private fun SelectionForest.computeDemandFromParentFields(
    world: Assumptions,
    parentDemandByResolverField: MutableMap<ViaductSchema.ObjectField, ParentDemandAnalysis>,
): ParentDemandAnalysis =
    foldParentDemand { selection ->
        val parentFields =
            selection.possibleTypes.mapNotNullTo(linkedSetOf()) { possibleType ->
                (selection.objectKey(possibleType) as? ObjectEngineResult.ParentKey)?.field
            }
        if (parentFields.isNotEmpty()) {
            ParentDemandAnalysis(
                parentRequests =
                    parentFields.map { parentField ->
                        ParentRequest(
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
            val parentRequests = mutableListOf<ParentRequest>()
            nested.parentRequests.forEach { unguardedRequest ->
                val request =
                    ParentRequest(
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
                        field.fixedParentDemand(world, parentDemandByResolverField)
                    val guardedResolverInput =
                        resolverInput.guardedBy(selection.inclusionCondition)
                    localDemand += guardedResolverInput.localDemand
                    parentRequests += guardedResolverInput.parentRequests
                }
            }
            ParentDemandAnalysis(localDemand, parentRequests)
        }
    }

private fun ViaductSchema.ObjectField.fixedParentDemand(
    world: Assumptions,
    parentDemandByResolverField: MutableMap<ViaductSchema.ObjectField, ParentDemandAnalysis>,
): ParentDemandAnalysis =
    parentDemandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .withoutInclusionConditions()
            .computeDemandFromParentFields(world, parentDemandByResolverField)
            .also { demand -> parentDemandByResolverField[this] = demand }

private data class ParentRequest(
    val parentField: ViaductSchema.ObjectField,
    val demand: SelectionForest,
)

private data class ParentDemandAnalysis(
    val localDemand: SelectionForest = selectionForestOf(),
    val parentRequests: List<ParentRequest> = emptyList(),
) {
    operator fun plus(other: ParentDemandAnalysis): ParentDemandAnalysis =
        ParentDemandAnalysis(
            localDemand = localDemand + other.localDemand,
            parentRequests = parentRequests + other.parentRequests,
        )
}

private fun ParentDemandAnalysis.guardedBy(
    condition: InclusionCondition,
): ParentDemandAnalysis =
    ParentDemandAnalysis(
        localDemand = localDemand.guardedBy(condition),
        parentRequests =
            parentRequests.map { request ->
                ParentRequest(
                    parentField = request.parentField,
                    demand = request.demand.guardedBy(condition),
                )
            },
    )

private fun SelectionForest.foldParentDemand(
    transform: (Selection) -> ParentDemandAnalysis,
): ParentDemandAnalysis {
    var result = ParentDemandAnalysis()
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
