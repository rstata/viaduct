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
 * ### Invariant: engine-result-nullability
 *
 * A null child occurs only at a nullable type expression. For an [ObjectEngineResult] cell, the
 * child's expression is [ObjectEngineResult.Key.field]'s [Schema.OutputField.type]. For a
 * [ListEngineResult] element, it is [Schema.TypeExpr.List.elementType]. Object lookup and list
 * construction enforce this invariant.
 *
 * ### Equality
 *
 * Implementations compare result values structurally over their documented properties; schema
 * definitions within those properties use the canonical `==` equality documented by [Schema].
 */
sealed interface EngineResult {
    val type: Schema.TypeExpr<Schema.OutputType>

    val baseType: Schema.OutputType
        get() = type.baseType
}

/**
 * An object result similar to the result of the GraphQL ExecuteSelectionSet algorithm.
 *
 * Extensional value equality is intentionally encapsulated by [equals] rather than exposing the
 * set of present [Key] instances for now. Two object results are equal exactly when their
 * [type] expressions match and, for every possible key, their [fetch] lookups return equal [Cell]
 * values or are both missing. Missingness means that the lookup throws [MissingFieldException].
 * Although callers cannot enumerate the lookup domain, equality includes both that domain and
 * its results.
 *
 * ### Invariant: oer-present-key-validity
 *
 * Every present [Key] has a canonical [Key.field] whose
 * [Schema.OutputField.containingType] is a concrete [Schema.ObjectType], never an abstract
 * [Schema.InterfaceType] or [Schema.UnionType], and that concrete type equals [baseType]. Its
 * argument fields are fully coerced to non-variable values: no argument recursively contains a
 * [Schema.VariableValue]. Consequently, the identity of keys present in an OER is canonical and
 * does not depend on conservative symbolic equality.
 *
 * ### Invariant: oer-error-argument-cell
 *
 * For every present [Key] whose arguments recursively contain [Schema.ErrorValue], [fetch]
 * returns a [Cell] whose [Cell.value] and [Cell.check] are both [Schema.ErrorValue].
 */
sealed class ObjectEngineResult : EngineResult {
    abstract override val type: Schema.TypeExpr.Named<Schema.ObjectType>
    final override val baseType: Schema.ObjectType
        get() = type.baseType

    /**
     * A key for one canonical schema output field and its arguments.
     *
     * ### Invariant: oer-key-argument-definition
     *
     * `arguments.type == field.arguments`.
     *
     * ### Usage
     *
     * When a key is used outside an OER, such as in a selection, [field]'s containing type may be
     * abstract and [arguments] may contain [Schema.VariableValue] instances. [ObjectEngineResult]
     * documents the additional constraints on keys present in an OER. Aliases do not participate in
     * identity.
     *
     * Construct keys through [Schema.objectEngineResultKey]. Equality is structural over [field]
     * and [arguments], using the canonical schema equality documented by [Schema].
     */
    @ConsistentCopyVisibility
    data class Key internal constructor(
        val field: Schema.OutputField,
        val arguments: Schema.ArgumentsValue,
    ) {
        init {
            require(arguments.type == field.arguments) {
                "Key arguments do not belong to its output field"
            }
        }
    }

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
    suspend fun fetch(key: Key): Cell {
        val cell = fetchCell(key)
        check(key.field.type.isNullable || cell.value != null) {
            "Non-null field ${key.field.containingType.typeName}/${key.field.fieldName} " +
                "cannot contain a null engine result"
        }
        return cell
    }

    protected abstract suspend fun fetchCell(key: Key): Cell
}

class ListEngineResult private constructor(
    override val type: Schema.TypeExpr.List<Schema.OutputType>,
    private val elements: List<EngineResult?>,
) : EngineResult,
    List<EngineResult?> by elements {
    init {
        if (!type.elementType.isNullable) {
            require(elements.none { it == null }) {
                "Non-null list element type cannot contain a null engine result"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ListEngineResult &&
            type == other.type &&
            elements == other.elements

    override fun hashCode(): Int = 31 * type.hashCode() + elements.hashCode()

    override fun toString(): String = elements.toString()

    internal companion object {
        fun create(
            type: Schema.TypeExpr.List<Schema.OutputType>,
            elements: List<EngineResult?>,
        ): ListEngineResult =
            ListEngineResult(
                type = type,
                elements = elements.toList(),
            )
    }
}
