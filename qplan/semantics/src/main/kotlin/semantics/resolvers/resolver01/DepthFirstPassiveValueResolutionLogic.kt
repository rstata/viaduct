package semantics.resolvers.resolver01

import model.EngineResultCell
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.RootFieldReferenceData
import model.SelectionForest
import semantics.resolvers.GroundedFieldPublicationOccurrence
import semantics.shared.OEROccurrence
import semantics.shared.SharedPassiveValueResolutionLogic
import semantics.shared.applicableGroundSelections
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** Grounded selection collection and reference dispatch for the shared passive traversal. */
internal class DepthFirstPassiveValueResolutionLogic(
    operation: DepthFirstOperationContext,
) : SharedPassiveValueResolutionLogic<DepthFirstOrchestrationTask, DepthFirstOperationContext>(operation) {
    override fun createOrchestrationTask(
        occurrence: OEROccurrence,
        source: EngineObjectData.Sync,
        constructionDemand: SelectionForest,
    ): DepthFirstOrchestrationTask =
        DepthFirstOrchestrationTask.create(operation, occurrence, source, constructionDemand)

    override fun collect(selections: SelectionForest, type: ViaductSchema.Object): ObjectSelectionForest =
        selections.applicableGroundSelections(operation, type)

    override fun resolveListReference(
        reference: RootFieldReferenceData,
        cell: EngineResultCell,
        path: List<PathComponent>,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        selection: ObjectSelection,
        invocationDemand: SelectionForest,
        parent: OEROccurrence,
    ) {
        operation.dispatcher.dispatchFieldResolver(
            GroundedFieldPublicationOccurrence(
                operation, parent, selection, cell, reference, invocationDemand, path, expectedType,
            ),
        )
    }
}
