package semantics.resolvers.resolver21

import model.EngineResultCell
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.RootFieldReferenceData
import model.SelectionForest
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedResolvePassiveValues
import semantics.shared.applicableGroundSelections
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** Grounded selection collection and field-task dispatch around the common passive traversal. */
internal class CoroutineResolvePassiveValues(private val resolverOperation: CoroutineOperationContext) :
    SharedResolvePassiveValues<CoroutineOrchestrationTask>(resolverOperation) {
    override fun createOrchestrationTask(
        occurrence: OEROccurrenceContext,
        source: EngineObjectData.Sync,
        constructionDemand: SelectionForest,
    ): CoroutineOrchestrationTask =
        CoroutineOrchestrationTask.create(resolverOperation, occurrence, source, constructionDemand)

    override fun collect(selections: SelectionForest, type: ViaductSchema.Object): ObjectSelectionForest =
        context(operation) { selections.applicableGroundSelections(type) }

    override fun resolveListReference(
        reference: RootFieldReferenceData,
        cell: EngineResultCell,
        path: List<PathComponent>,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        selection: ObjectSelection,
        invocationDemand: SelectionForest,
        parent: OEROccurrenceContext,
    ) {
        CoroutineFieldResolverTask.launchForListElement(
            CoroutineFieldResolverContext(
                resolverOperation, parent, selection, cell, reference, invocationDemand, path, expectedType,
            ),
        )
    }
}
