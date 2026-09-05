package semantics.resolvers

import model.Assumptions
import model.ObjectEngineResult
import model.Selection
import model.SelectionForest
import model.objectKey
import model.requireField
import model.selectionForestOf
import viaduct.graphql.schema.ViaductSchema

/**
 * Returns construction demand induced by parent selections in requested descendants and in the
 * fixed inputs of resolver boundaries reached from those descendants.
 */
context(world: Assumptions)
internal fun SelectionForest.inputParentDemand(): SelectionForest =
    if (world.parentFieldRelations.isEmpty()) {
        selectionForestOf()
    } else {
        analyzeInputParentDemand(mutableMapOf()).localDemand
    }

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
