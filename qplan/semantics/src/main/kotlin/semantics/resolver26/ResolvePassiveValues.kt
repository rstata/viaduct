package semantics.resolver26

import model.EngineResult
import model.EngineResultCell
import model.InclusionCondition
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.merge
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedResolvePassiveValues
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

context(operation: OperationContext)
internal fun ResolverOutputData?.resolvePassiveValues(
    root: ObjectEngineResult,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    invocationDemand: SelectionForest,
    constructionDemand: SelectionForest,
    parent: OEROccurrenceContext? = null,
): EngineResult? =
    ResolvePassiveValues(operation).resolvePassiveValues(
        this, root, expectedType, path, constructionDemand, invocationDemand, parent,
    )

/** Only symbolic demand and task dispatch are specific to Resolver26. */
private class ResolvePassiveValues(
    private val resolverOperation: OperationContext,
) : SharedResolvePassiveValues<OrchestrationTask>(resolverOperation) {
    override fun createOrchestrationTask(
        occurrence: OEROccurrenceContext,
        source: EngineObjectData.Sync,
        constructionDemand: SelectionForest,
    ): OrchestrationTask =
        OrchestrationTask.create(resolverOperation, occurrence, source, constructionDemand)

    override fun collect(selections: SelectionForest, type: ViaductSchema.Object): ObjectSelectionForest =
        selections.merge(type)

    override fun resolveListReference(
        reference: RootFieldReferenceData,
        cell: EngineResultCell,
        path: List<PathComponent>,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        selection: ObjectSelection,
        invocationDemand: SelectionForest,
        parent: OEROccurrenceContext,
    ) {
        cell.createValuePromise()
        resolverOperation.cycleChecker.registerWriter(cell, path)
        FieldResolverTask.launchForListElement(
            operationContext = resolverOperation,
            oerOccurrenceContext = parent,
            resolverOccurrenceContext = RootFieldReferenceOccurrence(
                selection = selection,
                reference = reference,
                publicationPath = path,
                publicationExpectedType = expectedType,
            ),
            publicationCell = cell,
        )
    }

    override fun deferReferenceList(
        occurrence: OEROccurrenceContext,
        selection: ObjectSelection,
        value: ResolverOutputData?,
        invocationDemand: SelectionForest,
        constructionDemand: SelectionForest,
    ): Boolean {
        if (selection.inclusionCondition === InclusionCondition.Always) return false
        if (selection.inclusionCondition !== InclusionCondition.Never) {
            FieldResolverTask.installAndLaunch(
                operationContext = resolverOperation,
                oerOccurrenceContext = occurrence,
                resolverOccurrenceContext = PassiveValueOccurrence(
                    selection = selection,
                    value = value,
                    invocationDemand = invocationDemand,
                    publicationConstructionDemand = constructionDemand,
                    publicationPath = occurrence.coordinate(selection.key),
                ),
            )
        }
        return true
    }
}
