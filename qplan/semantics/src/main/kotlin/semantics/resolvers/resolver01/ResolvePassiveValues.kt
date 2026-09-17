package semantics.resolvers.resolver01

import model.EngineResultCell
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.RootFieldReferenceData
import model.Selection
import model.SelectionForest
import model.merge
import model.selectionForestOf
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedResolvePassiveValues
import semantics.shared.applicableGroundSelections
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** Grounded selection collection and reference dispatch for the shared passive traversal. */
internal class DepthFirstPassiveValues(
    private val depthFirstOperation: DepthFirstOperationContext,
) : SharedResolvePassiveValues<DepthFirstOrchestrationTask>(depthFirstOperation) {
    override fun createOrchestrationTask(
        occurrence: OEROccurrenceContext,
        source: EngineObjectData.Sync,
        constructionDemand: SelectionForest,
    ): DepthFirstOrchestrationTask =
        DepthFirstOrchestrationTask.create(depthFirstOperation, occurrence, source, constructionDemand)

    override fun collect(selections: SelectionForest, type: ViaductSchema.Object): ObjectSelectionForest =
        context(operation) { selections.applicableGroundSelections(type) }

    override fun resolveListReference(
        reference: RootFieldReferenceData,
        cell: EngineResultCell,
        path: List<PathComponent>,
        expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
        constructionDemand: SelectionForest,
        invocationDemand: SelectionForest,
        parent: OEROccurrenceContext,
    ) {
        val key = path.filterIsInstance<ObjectEngineResult.ObjectKey>().last()
        val selection = selectionForestOf(
            Selection.of(key, setOf(parent.target.type), constructionDemand),
        ).merge(parent.target.type).byKey().getValue(key)
        depthFirstOperation.dispatcher.dispatchFieldResolver(
            DepthFirstFieldResolverTask(
                depthFirstOperation, parent, selection, cell, reference, invocationDemand, path, expectedType,
            ),
        )
    }
}
