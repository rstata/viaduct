package semantics.resolvers.resolver21

import java.util.concurrent.atomic.AtomicBoolean
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.RootFieldReferenceData
import model.SelectionForest
import model.outputValue
import model.schemaType
import semantics.resolvers.closeResolverDemand
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedOrchestrationTask
import semantics.shared.installParentBackedgeFields
import viaduct.engine.api.EngineObjectData

/** Closes one object's demand before passive descent, then installs and launches its field tasks. */
internal class CoroutineOrchestrationTask private constructor(
    internal val operation: CoroutineOperationContext,
    override val occurrence: OEROccurrenceContext,
    override val source: EngineObjectData.Sync,
    override val closedDemand: ObjectSelectionForest,
) : SharedOrchestrationTask {
    private val launched = AtomicBoolean(false)

    companion object {
        /** Prepares grounded bindings and parent backedges without dispatching active work. */
        fun create(
            operation: CoroutineOperationContext,
            occurrence: OEROccurrenceContext,
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
            occurrence.installParentBackedgeFields(parentKeys)
            CoroutineOrchestrationTask(operation, occurrence, source, closed)
        }
    }

    internal val hasActiveWork: Boolean
        get() = closedDemand.groundKeys().any { key ->
            key !is ObjectEngineResult.ParentKey &&
                (!source.isPresent(key.field.name) || source.outputValue(key.field.name) is RootFieldReferenceData)
        }

    /** Checks the same one-shot dispatch boundary as Resolver26. */
    internal fun checkDispatch() {
        check(launched.compareAndSet(false, true)) { "Object orchestrated twice: ${occurrence.path}" }
    }

    internal fun run() {
        CoroutineFieldResolverTask.launchAll(this, closedDemand)
        occurrence.target.freeze()
    }
}
