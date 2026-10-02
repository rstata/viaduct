package semantics.shared

import model.EngineResultCell
import model.ObjectSelectionForest
import viaduct.engine.api.EngineObjectData

/**
 * Prepared orchestration task for one object. Its factory closes demand and establishes the
 * state needed by descendants before returning; passive resolution uses [closedDemand] before
 * handing the task to [SharedTaskDispatcher.dispatchOrchestrator]. The task supplies its owning
 * context through [operation]; it is not itself an operation context.
 */
interface SharedOrchestrationTask<out O : SharedOperationContext<*>> {
    /** The owning operation, retaining its resolver-specific type. */
    val operation: O
    val occurrence: OEROccurrence
    /** Source-owned passive values; Query roots supply an empty object. */
    val source: EngineObjectData.Sync
    val closedDemand: ObjectSelectionForest
}

/**
 * One field or list-element publication, with its operation, containing object, and destination cell.
 * Also supplies the owning operation contract, implemented by delegation to [operation].
 */
interface SharedFieldPublicationOccurrence<
    out O : SharedOperationContext<D>,
    out D : SharedTaskDispatcher<Nothing, Nothing>,
> : SharedOperationContext<D> {
    /** The owning operation; implementations may specialize its type for their resolver. */
    val operation: O
    val oerOccurrence: OEROccurrence
    val publicationCell: EngineResultCell
}

/**
 * Schedules the two resolver task kinds. Implementations own coroutine launch, queue ordering, or
 * recursive execution; task contexts retain the inputs needed by the corresponding task bodies.
 * [O] preserves the concrete orchestration-task type and [F] the field-publication occurrence type.
 */
interface SharedTaskDispatcher<in O : SharedOrchestrationTask<*>, in F : SharedFieldPublicationOccurrence<*, *>> {
    /** Dispatches prepared object work after its passive fields have been resolved. */
    fun dispatchOrchestrator(task: O)

    /** Dispatches field work according to this resolver's dependency-ordering policy. */
    fun dispatchFieldResolver(publication: F)
}
