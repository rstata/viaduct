package semantics.shared

/** A running field-resolution task with its owning operation and object occurrence. */
internal interface SharedFieldResolverTask {
    /** The owning operation; concrete tasks may specialize its type for their resolver. */
    val operationContext: SharedOperationContext<*>
    val oerOccurrenceContext: OEROccurrenceContext
}
