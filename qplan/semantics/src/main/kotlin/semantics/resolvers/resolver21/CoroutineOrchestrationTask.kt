package semantics.resolvers.resolver21

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.RootFieldReferenceData
import model.SelectionForest
import model.outputValue
import model.schemaType
import semantics.resolvers.closeResolverDemand
import semantics.shared.OEROccurrence
import semantics.shared.installParentBackedgeFields
import viaduct.engine.api.EngineObjectData

/** Closes one object's demand before passive descent, then installs and launches its field tasks. */
internal class CoroutineOrchestrationTask private constructor(
    operation: CoroutineOperationContext,
    occurrence: OEROccurrence,
    source: EngineObjectData.Sync,
    override val closedDemand: ObjectSelectionForest,
) : semantics.resolver26.CoroutineOrchestrationTask<CoroutineOperationContext>(operation, occurrence, source) {
    companion object {
        /** Prepares grounded bindings and parent backedges without dispatching active work. */
        fun create(
            operation: CoroutineOperationContext,
            occurrence: OEROccurrence,
            source: EngineObjectData.Sync,
            initialDemand: SelectionForest,
        ): CoroutineOrchestrationTask = context(operation) {
            require(source.schemaType == occurrence.target.type) {
                "Source type ${source.schemaType.name} does not match result type ${occurrence.target.type.name}"
            }
            val closed = source.closeResolverDemand(
                occurrence.root, occurrence.path, initialDemand,
                includeParentInputDemand = operation.supportsParentFields,
            )
            val parentKeys = closed.groundKeys().filterIsInstance<ObjectEngineResult.ParentKey>()
            require(operation.supportsParentFields || parentKeys.isEmpty()) {
                "Resolver21 does not support @parent fields"
            }
            occurrence.installParentBackedgeFields(operation, parentKeys)
            CoroutineOrchestrationTask(operation, occurrence, source, closed)
        }
    }

    override val hasActiveWork: Boolean
        get() = closedDemand.groundKeys().any { key ->
            key !is ObjectEngineResult.ParentKey &&
                (!source.isPresent(key.field.name) || source.outputValue(key.field.name) is RootFieldReferenceData)
        }

    override fun installFieldTasks() {
        CoroutineFieldResolverTask.launchAll(this, closedDemand)
    }
}
