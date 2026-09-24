package semantics.resolver26

import model.Assumptions
import model.ObjectEngineResult
import model.ObjectSelection
import model.Selection
import model.SelectionForest
import model.InclusionCondition
import model.guardedBy
import model.objectKey
import model.selectionForestOf
import viaduct.graphql.schema.ViaductSchema

/**
 * Returns additional construction demand induced by parent selections in requested descendants
 * and in the fixed inputs of resolver boundaries reached from those descendants. The caller adds
 * this contribution to its original demand; the two may overlap.
 *
 * The worked examples in `ParentConstructionDemandTest` are the best introduction to this
 * operation, especially its recursive lifting through intermediate OERs and resolver inputs.
 */
internal fun SelectionForest.liftParentConstructionDemand(world: Assumptions): SelectionForest =
    if (world.parentFieldRelations.isEmpty()) {
        selectionForestOf()
    } else {
        analyzeParentDemandInSelectionForest(world, mutableMapOf()).localDemand
    }

/**
 * Intermediate result of lifting parent-induced demand through a selection tree.
 *
 * [localDemand] is additional demand already placed at the current object's level.
 * [parentRequests] are demands reached through `@parent` fields whose matching producer field
 * has not yet been encountered; enclosing selections carry them outward until that producer edge
 * can transpose their demand onto the parent object.
 */
private class ParentDemandAnalysis(
    val localDemand: SelectionForest = selectionForestOf(),
    val parentRequests: List<ParentRequest> = emptyList(),
) {
    operator fun plus(other: ParentDemandAnalysis): ParentDemandAnalysis =
        ParentDemandAnalysis(
            localDemand = localDemand + other.localDemand,
            parentRequests = parentRequests + other.parentRequests,
        )
}

private class ParentRequest(
    val parentField: ViaductSchema.ObjectField,
    val demand: SelectionForest,
)

private fun SelectionForest.analyzeParentDemandInSelectionForest(
    world: Assumptions,
    parentDemandByResolverField: MutableMap<ViaductSchema.ObjectField, ParentDemandAnalysis>,
): ParentDemandAnalysis {
    var result = ParentDemandAnalysis()
    forEach { selection ->
        // An abstract field can be a parent link on one implementation and ordinary on another.
        // Specialize before choosing either case, preserving each contribution's type condition.
        selection.possibleTypes.forEach { type ->
            result +=
                Selection.of(
                    key = selection.objectKey(type),
                    possibleTypes = setOf(type),
                    subselections = selection.subselections,
                    inclusionCondition = selection.inclusionCondition,
                ).analyzeParentDemandInObjectSelection(world, parentDemandByResolverField)
        }
    }
    return result
}

private fun ObjectSelection.analyzeParentDemandInObjectSelection(
    world: Assumptions,
    parentDemandByResolverField: MutableMap<ViaductSchema.ObjectField, ParentDemandAnalysis>,
): ParentDemandAnalysis {
    if (key is ObjectEngineResult.ParentKey) {
        return ParentDemandAnalysis(
            parentRequests =
                listOf(
                    ParentRequest(
                        key.field,
                        subselections.guardedBy(inclusionCondition),
                    ),
                ),
        )
    }

    val nested =
        subselections.analyzeParentDemandInSelectionForest(world, parentDemandByResolverField)
    var localDemand =
        if (nested.localDemand.isEmpty()) {
            selectionForestOf()
        } else {
            selectionForestOf(
                Selection.of(
                    key = key,
                    possibleTypes = possibleTypes,
                    subselections = nested.localDemand,
                    inclusionCondition = inclusionCondition,
                ),
            )
        }
    val parentRequests = mutableListOf<ParentRequest>()
    nested.parentRequests.forEach { unguardedRequest ->
        val request =
            ParentRequest(
                parentField = unguardedRequest.parentField,
                demand = unguardedRequest.demand.guardedBy(inclusionCondition),
            )
        if (world.parentFieldRelations[request.parentField] == key.field) {
            val ancestor =
                request.demand.analyzeParentDemandInSelectionForest(world, parentDemandByResolverField)
            localDemand += request.demand + ancestor.localDemand
            parentRequests += ancestor.parentRequests
        }
    }
    val field = key.field
    if (field in world.resolverRegistry) {
        val resolverInput = field.fixedParentDemand(world, parentDemandByResolverField)
        val guardedResolverInput = resolverInput.guardedBy(inclusionCondition)
        localDemand += guardedResolverInput.localDemand
        parentRequests += guardedResolverInput.parentRequests
    }
    return ParentDemandAnalysis(localDemand, parentRequests)
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
            .analyzeParentDemandInSelectionForest(world, parentDemandByResolverField)
            .also { demand -> parentDemandByResolverField[this] = demand }

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
