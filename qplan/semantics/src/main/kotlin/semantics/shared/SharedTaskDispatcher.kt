package semantics.shared

import model.EngineResultCell
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * Prepared orchestration task for one object. Its factory closes demand and establishes the
 * state needed by descendants before returning; passive resolution uses [closedDemand] before
 * handing the task to [SharedTaskDispatcher.dispatchOrchestrator].
 */
interface SharedOrchestrationTask {
    val occurrence: OEROccurrenceContext
    val source: EngineObjectData.Sync
    val closedDemand: ObjectSelectionForest
}

/** Stable occurrence and publication inputs for a field-resolution task. */
interface SharedFieldResolverContext {
    /** The owning operation; implementations may specialize its type for their resolver. */
    val operationContext: SharedOperationContext<*>
    val oerOccurrenceContext: OEROccurrenceContext
    val selection: ObjectSelection
    val publicationCell: EngineResultCell
    val publicationPath: List<PathComponent>
    val publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>
    val publicationConstructionDemand: SelectionForest
}

/**
 * Schedules the two resolver task kinds. Implementations own coroutine launch, queue ordering, or
 * recursive execution; task contexts retain the inputs needed by the corresponding task bodies.
 * [O] and [F] preserve the concrete task types accepted by each implementation.
 */
interface SharedTaskDispatcher<in O : SharedOrchestrationTask, in F : SharedFieldResolverContext> {
    /** Dispatches prepared object work after its passive fields have been resolved. */
    fun dispatchOrchestrator(task: O)

    /** Dispatches field work according to this resolver's dependency-ordering policy. */
    fun dispatchFieldResolver(context: F)
}
