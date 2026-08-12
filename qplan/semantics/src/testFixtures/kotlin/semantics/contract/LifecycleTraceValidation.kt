package semantics.contract

/** One externally detected lifecycle-trace invariant violation. */
data class TraceViolation(
    val code: String,
    val sequence: Long?,
    val message: String,
)

/** One independently composable judgment over an immutable event trace. */
fun interface TraceValidator<E> {
    fun validate(trace: List<E>): List<TraceViolation>
}

fun <E> Iterable<TraceValidator<E>>.validate(
    trace: List<E>,
): List<TraceViolation> =
    flatMap { validator -> validator.validate(trace) }
