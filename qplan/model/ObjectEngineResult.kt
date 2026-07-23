package model

/**
 * A value in an [ObjectEngineResult] tree.
 *
 * This is an inductively defined algebraic value: every [EngineResult] is finite and
 * well-founded, and every child of a [ListEngineResult] or [ObjectEngineResult] is a strictly
 * smaller value. Kotlin object identity, reference sharing, self-reference, and cycles are not
 * part of this model. Implementations use structural value equality, never reference equality.
 */
sealed interface EngineResult {
    val typeName: String
}

/**
 * An object result similar to the result of the GraphQL ExecuteSelectionSet algorithm.
 *
 * Extensional value equality is intentionally encapsulated by [equals] rather than exposing the
 * set of present [Key] instances for now. Two object results are equal exactly when their
 * [typeName] values match and, for every possible key, their [fetch] lookups return equal [Cell]
 * values or are both missing. Missingness means that the lookup throws [MissingFieldException].
 * Although callers cannot enumerate the lookup domain, equality includes both that domain and
 * its results.
 *
 * For every present [Key] whose arguments recursively contain [GraphQLErrorValue], [fetch]
 * returns a [Cell] whose [Cell.value] and [Cell.check] are both [GraphQLErrorValue].
 */
sealed interface ObjectEngineResult : EngineResult {
    override val typeName: String

    /**
     * A field's identity in an OER. Aliases do not participate in identity.
     *
     * As an invariant, every argument value is fully coerced to a non-variable value:
     * no value recursively contains a [GraphQLVariableValue] anywhere. Consequently,
     * key identity is canonical and does not depend on conservative symbolic equality.
     */
    data class Key(
        val fieldName: String,
        val arguments: Map<String, GraphQLInputValue?>,
    )

    data class Cell(
        val value: EngineResult?,
        val check: GraphQLBooleanValue,
    )

    /**
     * Fetches the cell for [key].
     *
     * A null [Cell.value] represents a present field whose GraphQL value is null. A missing key
     * throws [MissingFieldException].
     *
     * In this mathematical model, lookup always terminates. Its only possible outcomes are
     * returning exactly one [Cell] or throwing [MissingFieldException].
     */
    @Throws(MissingFieldException::class)
    suspend fun fetch(key: Key): Cell
}

sealed interface ListEngineResult : EngineResult, List<EngineResult?> {
    override val typeName: String
        get() = "\$LIST"
}

