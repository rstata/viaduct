package model

/**
 * A value in an [ObjectEngineResult] tree.
 *
 * ### Invariant: engine-result-well-foundedness
 *
 * This is an inductively defined algebraic value: every [EngineResult] is finite and
 * well-founded, and every child of a [ListEngineResult] or [ObjectEngineResult] is a strictly
 * smaller value. Kotlin object identity, reference sharing, self-reference, and cycles are not
 * part of this model.
 *
 * ### Invariant: oer-result-nullability
 *
 * A null [ObjectEngineResult] cell occurs only when its [Schema.ObjectKey.field] has a nullable
 * [Schema.OutputField.type]. Object lookup enforces this invariant. List elements are typed by
 * their surrounding schema context rather than by the [ListEngineResult] carrier.
 *
 * ### Equality
 *
 * Implementations compare result values structurally over their documented properties; schema
 * definitions within those properties use the canonical `==` equality documented by [Schema].
 */
sealed interface EngineResult

/**
 * An object result similar to the result of the GraphQL ExecuteSelectionSet algorithm.
 *
 * [keys] exposes the finite domain of present [Schema.ObjectKey] instances. Two object results are
 * equal exactly when their concrete [type] and key sets match and [fetch] returns equal [Cell] values
 * for every present key.
 *
 * ### Invariant: oer-present-key-validity
 *
 * Every present [Schema.ObjectKey] has a canonical [Schema.ObjectKey.field] whose
 * [Schema.OutputField.containingType] is a concrete [Schema.ObjectType], never an abstract
 * [Schema.InterfaceType] or [Schema.UnionType], and that concrete type equals [type]. Its
 * argument fields are fully coerced to non-variable values: no argument recursively contains a
 * [Schema.VariableValue]. Consequently, the identity of keys present in an OER is canonical and
 * does not depend on conservative symbolic equality.
 *
 * ### Invariant: oer-error-argument-cell
 *
 * For every present [Schema.ObjectKey] whose arguments recursively contain [Schema.ErrorValue],
 * [fetch] returns a [Cell] whose [Cell.value] and [Cell.check] are both [Schema.ErrorValue].
 */
sealed class ObjectEngineResult : EngineResult {
    abstract val type: Schema.ObjectType

    /**
     * The finite set of keys present in this result.
     *
     * ### Invariant: oer-key-domain
     *
     * For every [Schema.ObjectKey] `key`, `key in keys` exactly when [fetch] returns one [Cell]; when
     * `key !in keys`, [fetch] throws [MissingFieldException]. The set has no modeled order.
     */
    abstract val keys: Set<Schema.ObjectKey>

    /**
     * One present field's resolved value and retained check component.
     *
     * [check] is an uninterpreted carrier value in the current model. This package does not define
     * how it is produced or what its Boolean value means; those rules belong to future checker
     * semantics. A semantic judgment may explicitly declare itself check-insensitive and observe
     * only field presence and [value]. Future checker-aware correctness judgments will interpret
     * and account for [check] alongside [value].
     */
    data class Cell(
        val value: EngineResult?,
        val check: Schema.BooleanValue,
    )

    /**
     * Fetches the cell for [key].
     *
     * A null [Cell.value] represents a present field whose GraphQL value is null. A missing key
     * throws [MissingFieldException].
     *
     * In this mathematical model, lookup always terminates. Its only possible outcomes are
     * returning exactly one [Cell] or throwing [MissingFieldException].
     *
     * @throws MissingFieldException when [key] is not present
     * @throws IllegalStateException when a non-null field contains a null value
     */
    suspend fun fetch(key: Schema.ObjectKey): Cell {
        val cell = fetchCell(key)
        check(key.field.type.isNullable || cell.value != null) {
            "Non-null field ${key.field.containingType.typeName}/${key.field.fieldName} " +
                "cannot contain a null engine result"
        }
        return cell
    }

    protected abstract suspend fun fetchCell(key: Schema.ObjectKey): Cell
}

class ListEngineResult private constructor(
    private val elements: List<EngineResult?>,
) : EngineResult,
    List<EngineResult?> by elements {
    override fun equals(other: Any?): Boolean =
        other is ListEngineResult &&
            elements == other.elements

    override fun hashCode(): Int = elements.hashCode()

    override fun toString(): String = elements.toString()

    internal companion object {
        fun create(elements: List<EngineResult?>): ListEngineResult =
            ListEngineResult(elements.toList())
    }
}
