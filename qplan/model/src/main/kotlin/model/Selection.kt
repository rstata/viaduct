package model

/**
 * A free commutative collection of opaque [Selection] occurrences.
 *
 * ### Invariant: selection-forest-occurrences
 *
 * Each flattened GraphQL field occurrence contributes one member, and [size] observes the total
 * number of occurrences.
 *
 * ### Equality And Mutability
 *
 * Selection and forest equality are undefined. This interface therefore exposes only operations
 * that preserve occurrences without comparing payloads or observing an order.
 */
sealed interface SelectionForest {
    val size: Int

    fun isEmpty(): Boolean

    fun all(predicate: (Selection) -> Boolean): Boolean

    fun filter(predicate: (Selection) -> Boolean): SelectionForest

    fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest

    fun <K> groupBy(keySelector: (Selection) -> K): Map<K, SelectionForest>

    fun forEach(action: (Selection) -> Unit)

    fun single(): Selection

    fun single(predicate: (Selection) -> Boolean): Selection

    operator fun plus(other: SelectionForest): SelectionForest
}

/** Constructs a [SelectionForest] containing the supplied occurrences. */
fun selectionForestOf(vararg selections: Selection): SelectionForest =
    SelectionForestImpl(selections.asList())

/** Constructs a [SelectionForest] from these occurrences. */
fun Iterable<Selection>.toSelectionForest(): SelectionForest =
    SelectionForestImpl(toList())

/**
 * A post-validation field-selection occurrence used for Viaduct field resolution.
 *
 * This is not a GraphQL AST selection or a description of field completion. Aliases, response
 * keys, source order, named fragments, inline-fragment nodes, and directives are absent. Inline
 * fragments have already been flattened into [nominalType] and [possibleTypes].
 *
 * ### Invariant: selection-local-coherence
 *
 * Selections constructed by this model use [of], which ensures:
 * - [key]'s field belongs to [nominalType].
 * - [possibleTypes] is a subset of the object types contained by [nominalType].
 * - When [isLeaf] is true, [subselections] is empty. The converse does not hold: a composite
 *   selection may also have no subselections.
 *
 * ### Invariant: selection-well-foundedness
 *
 * A selection and its [subselections] form a finite, well-founded value.
 *
 * ### Equality
 *
 * Kotlin `equals` is currently undefined for [Selection]. The model does not yet assume that
 * selections can or need to be compared; proofs must not rely on selection equality until that
 * requirement is established.
 */
sealed interface Selection {
    /**
     * Exact object-field coordinate being selected.
     *
     * ### Invariant: selection-argument-coercion
     *
     * The arguments form the post-validation tuple for the selected field: declared defaults have
     * been applied, every required argument is present, omitted optional arguments without defaults
     * are absent, and every present non-variable value conforms recursively to its argument type.
     *
     * ### Representation
     *
     * [Value.Key.field] is the canonical schema field intended by this selection, and
     * its containing type is [nominalType]. Non-variable argument values are in their coerced
     * semantic form. An argument may contain a [Value.Variable] when the variable is unbound.
     * Such keys use the model's conservative equality: they merge only when they are definitely
     * equal. Because a selection key is outside an OER or [Value.Object], its field may belong
     * to an abstract [Schema.InterfaceType] or [Schema.UnionType], and its arguments may contain
     * unresolved variables. Before a key is present in either value, its field must belong to the
     * applicable concrete [Schema.ObjectType] and its variables must be instantiated.
     *
     * Compared to GraphQL selections, field-resolver selections use the object key rather than
     * response keys.
     */
    val key: Value.Key

    /**
     * The immediate type context of [key].
     *
     * It is [possibleTypes], not the nominal type, that controls which concrete types this selection
     * actually applies to. This object must be contained in [Assumptions.schema].
     *
     * Compared to GraphQL selections, this is the type-condition of the immediately-enclosing
     * spread, or the nominal type inherited from an enclosing selection or document if there
     * haven't been any spreads applied.
     */
    val nominalType: Schema.CompositeType

    /**
     * The concrete runtime parent-object types for which this selection applies: if during resolution
     * this selection is applied to a type outside of this set, then the selection will be dropped.
     * In prose this property is often called the "type condition" of the selection.
     * All of these objects must be contained in [Assumptions.schema].
     *
     * Compared to GraphQL selections, this is the intersection of all the possible types of all the
     * containing spreads and the type-level type of the containing document.  Unlike GraphQL
     * selection, this is the true intersection: even when a "broadening" type condition is
     * applied in the GraphQL selection, we won't broaden this corresponding type condition.
     */
    val possibleTypes: Set<Schema.ObjectType>

    /**
     * The subselections on the value this selection selects.
     *
     * This is empty when [isLeaf] is true. It may also be empty when this selection selects a
     * composite type but no fields within that value.
     *
     * Because of GraphQL's validation rules for type conditions, the relationship
     * between the [nominalType] of a subselection and [key]'s field result type is complicated.
     * Nested type conditions nested spreads need only overlap pairwise:
     * ```
     *    I1 = {A, B}
     *    I2 = {B, C}
     *    I3 = {C, D}
     * ```
     * Under an I1 field, ... on I2 { ... on I3 { x } } is valid, but I3 does not overlap I1.
     * So while we might someday need an invariant for the [nominalType] of subselections,
     * right now we decline to state one.
     *
     * One might imagine an invariant that looks at the _actual_ object types that [key] could be applied
     * to (according to the local [possibleTypes]) and constrain those sub-[possibleTypes] to
     * contain the union of just those.  However, we don't think we need that invariant so currently
     * it is **not** an invariant of this data type.
     *
     * Compared to GraphQL selections, descending into this property _always_ descends a level
     * into the GraphQL object-value tree.  (In GraphQL selections, subselections can be spreads,
     * which do **not** descend into the object-value tree.)
     */
    val subselections: SelectionForest

    /**
     * Whether this selection's field has a simple base output type.
     *
     * The field's type expression may wrap that base type in lists or non-null constraints.
     */
    val isLeaf: Boolean
        get() = key.field.typeExpr.baseType is Schema.SimpleType

    companion object {
        /**
         * Constructs a selection that satisfies the invariant documented on [Selection].
         */
        fun of(
            key: Value.Key,
            nominalType: Schema.CompositeType,
            possibleTypes: Set<Schema.ObjectType>,
            subselections: SelectionForest,
        ): Selection {
            require(key.field.containingType == nominalType) {
                "Selection field ${key.field.fieldName} does not belong to ${nominalType.typeName}"
            }
            require(possibleTypes.all { it in nominalType.possibleTypes }) {
                "Selection possible types must be contained by ${nominalType.typeName}"
            }
            require(
                key.field.typeExpr.baseType is Schema.CompositeType || subselections.isEmpty(),
            ) {
                "Leaf selection ${nominalType.typeName}.${key.field.fieldName} has subselections"
            }

            return SelectionImpl(
                key = key,
                nominalType = nominalType,
                possibleTypes = possibleTypes,
                subselections = subselections,
            )
        }
    }
}

private class SelectionImpl(
    override val key: Value.Key,
    override val nominalType: Schema.CompositeType,
    override val possibleTypes: Set<Schema.ObjectType>,
    override val subselections: SelectionForest,
) : Selection

private class SelectionForestImpl(
    private val occurrences: List<Selection>,
) : SelectionForest {
    override val size: Int
        get() = occurrences.size

    override fun isEmpty(): Boolean = occurrences.isEmpty()

    override fun all(predicate: (Selection) -> Boolean): Boolean = occurrences.all(predicate)

    override fun filter(predicate: (Selection) -> Boolean): SelectionForest =
        SelectionForestImpl(occurrences.filter(predicate))

    override fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest =
        SelectionForestImpl(
            occurrences.flatMap { selection ->
                (transform(selection) as SelectionForestImpl).occurrences
            },
        )

    override fun <K> groupBy(keySelector: (Selection) -> K): Map<K, SelectionForest> =
        occurrences
            .groupBy(keySelector)
            .mapValues { (_, selections) -> SelectionForestImpl(selections) }

    override fun forEach(action: (Selection) -> Unit) {
        occurrences.forEach(action)
    }

    override fun single(): Selection = occurrences.single()

    override fun single(predicate: (Selection) -> Boolean): Selection =
        occurrences.single(predicate)

    override fun plus(other: SelectionForest): SelectionForest =
        SelectionForestImpl(occurrences + (other as SelectionForestImpl).occurrences)
}
