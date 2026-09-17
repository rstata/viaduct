package semantics.resolvers.resolver01

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.RootFieldReferenceData
import model.SelectionForest
import model.outputValue
import model.schemaType
import semantics.resolvers.closeResolverDemand
import semantics.shared.OEROccurrenceContext
import semantics.shared.SharedOrchestrationTask
import viaduct.engine.api.EngineObjectData

/** The two executable task kinds accepted by either depth-first dispatcher. */
internal sealed interface DepthFirstTask {
    /** Scheduling depth: the containing path for fields, and the object's own path for orchestration. */
    val path: List<PathComponent>
}

/** Prepared grounded demand and active-field dispatch for Resolver01-03 and Resolver06-08. */
internal class DepthFirstOrchestrationTask private constructor(
    private val operation: DepthFirstOperationContext,
    override val occurrence: OEROccurrenceContext,
    override val source: EngineObjectData.Sync,
    override val closedDemand: ObjectSelectionForest,
) : SharedOrchestrationTask, DepthFirstTask {
    override val path get() = occurrence.path

    /**
     * Dispatches source references first, then standard fields in sibling dependency order.
     * Recursive execution finishes each field's fringe here; a reactor leaves that to its queue.
     */
    fun run(resolveFringe: () -> Unit = {}): Unit = context(operation, operation.world) {
        val target = occurrence.target
        val unresolved = closedDemand.byGroundKey().filterKeys { !target.isCellSet(it) }
        val references = unresolved.keys.mapNotNull { key ->
            val reference = if (source.isPresent(key.field.name)) {
                source.outputValue(key.field.name) as? RootFieldReferenceData
            } else null
            reference?.let { key to it }
        }.toMap()
        fun dispatch(key: ObjectEngineResult.GroundKey, reference: RootFieldReferenceData? = null) {
            operation.dispatcher.dispatchFieldResolver(
                DepthFirstFieldResolverTask(
                    operation, occurrence, unresolved.getValue(key), target.reserveCell(key), reference,
                ),
            )
            resolveFringe()
        }
        references.forEach { (key, reference) -> dispatch(key, reference) }
        dependencyOrder(occurrence.root, path, unresolved.keys - references.keys)
            .forEach { key -> dispatch(key) }
        target.freeze()
    }

    companion object {
        /**
         * Closes grounded demand before passive descent. Query roots supply an empty [source]
         * because every field uses a registered resolver; their task can be dispatched directly.
         */
        fun create(
            operation: DepthFirstOperationContext,
            occurrence: OEROccurrenceContext,
            source: EngineObjectData.Sync,
            constructionDemand: SelectionForest,
        ): DepthFirstOrchestrationTask = context(operation) {
            require(source.schemaType == occurrence.target.type) {
                "Source type ${source.schemaType.name} does not match result type ${occurrence.target.type.name}"
            }
            val closed = source.closeResolverDemand(occurrence.root, occurrence.path, constructionDemand)
            require(closed.groundKeys().none { it is ObjectEngineResult.ParentKey }) {
                "Resolver01-03 and Resolver06-08 do not support @parent fields"
            }
            DepthFirstOrchestrationTask(operation, occurrence, source, closed)
        }
    }
}
