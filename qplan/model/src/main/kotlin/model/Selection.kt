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
 * Selection and forest equality are undefined. Except for the explicit [merge] normalization
 * boundary, this interface exposes only operations that preserve occurrences without comparing
 * payloads or observing an order.
 */
sealed interface SelectionForest {
    val size: Int

    fun isEmpty(): Boolean

    fun all(predicate: (Selection) -> Boolean): Boolean

    fun filter(predicate: (Selection) -> Boolean): SelectionForest

    fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest

    /** The union of the sets produced independently from each occurrence. */
    fun <T> flatMapToSet(transform: (Selection) -> Set<T>): Set<T>

    /** The structural keys contributed independently by all occurrences. */
    fun keys(): Set<Value.Key>

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
 * Returns the demand applicable to concrete parent [type], normalized by structural key.
 *
 * Applicability is tested before specialization, so an inapplicable occurrence contributes neither
 * its key nor its descendants. Each applicable key is reconstructed against the canonical field on
 * [type], including concrete argument defaults, and occurrences with equal reconstructed keys are
 * replaced by one selection whose subselections are their concatenated subselections.
 *
 * The result has exactly one top-level selection for each reconstructed key. Every result key's
 * field belongs to [type], and every result selection has `possibleTypes == setOf(type)`.
 * Subselections are not recursively merged because their concrete runtime parent type is not yet
 * known.
 *
 * A reconstructed key may still contain [Value.Variable] values. Such a key is a symbolic
 * concrete-field coordinate and cannot inhabit a [Value.Object] or [EngineResult.Object] until its
 * variables are instantiated. Merging uses ordinary structural key equality and must be repeated
 * after substitution can make formerly distinct argument tuples equal.
 */
context(world: Assumptions)
fun SelectionForest.merge(type: Schema.ObjectType): SelectionForest =
    filter { selection -> type in selection.possibleTypes }
        .groupBy { selection ->
            Value.Key.of(
                field = world.schema.field(type.typeName, selection.key.field.fieldName),
                arguments = selection.key.arguments.fieldValues,
            )
        }.entries
        .fold(selectionForestOf()) { result, (key, selections) ->
            result +
                selectionForestOf(
                    Selection.of(
                        key = key,
                        possibleTypes = setOf(type),
                        subselections =
                            selections.flatMap { selection -> selection.subselections },
                    ),
                )
        }

/**
 * A post-validation field-selection occurrence used for Viaduct field resolution.
 *
 * This is not a GraphQL AST selection or a description of field completion. Aliases, response
 * keys, source order, named fragments, inline-fragment nodes, and directives are absent. Inline
 * fragments have already been flattened into the field coordinate in [key] and the applicability
 * guard in [possibleTypes].
 *
 * ### Invariant: selection-local-coherence
 *
 * Selections constructed by this model use [of], which ensures:
 * - [possibleTypes] is a subset of the object types contained by [key]'s field owner.
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
     * its containing type records the immediate field-lookup context. Non-variable argument values
     * are in their coerced semantic form. An argument may contain a [Value.Variable]. Variables
     * compare by name. Because a selection key is outside an OER or [Value.Object], its field may
     * belong to an abstract [Schema.InterfaceType] or [Schema.UnionType], and its arguments may
     * contain variables. Before a key is present in either value, its field must belong to the
     * applicable concrete [Schema.ObjectType] and its variables must be instantiated.
     *
     * Compared to GraphQL selections, field-resolver selections use the object key rather than
     * response keys.
     */
    val key: Value.Key

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
     * Because of GraphQL's validation rules for type conditions, the relationship between the
     * field owner of a subselection and [key]'s field result type is complicated. Nested type
     * conditions need only overlap pairwise:
     * ```
     *    I1 = {A, B}
     *    I2 = {B, C}
     *    I3 = {C, D}
     * ```
     * Under an I1 field, ... on I2 { ... on I3 { x } } is valid, but I3 does not overlap I1.
     * So while we might someday need an invariant for the field owners of subselections, right now
     * we decline to state one.
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
            possibleTypes: Set<Schema.ObjectType>,
            subselections: SelectionForest,
        ): Selection {
            val fieldOwner = key.field.containingType
            require(possibleTypes.all { it in fieldOwner.possibleTypes }) {
                "Selection possible types must be contained by ${fieldOwner.typeName}"
            }
            require(
                key.field.typeExpr.baseType is Schema.CompositeType || subselections.isEmpty(),
            ) {
                "Leaf selection ${fieldOwner.typeName}.${key.field.fieldName} has subselections"
            }

            return SelectionImpl(
                key = key,
                possibleTypes = possibleTypes,
                subselections = subselections,
            )
        }
    }
}

private class SelectionImpl(
    override val key: Value.Key,
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

    override fun <T> flatMapToSet(transform: (Selection) -> Set<T>): Set<T> =
        occurrences.fold(emptySet()) { result, selection -> result + transform(selection) }

    override fun keys(): Set<Value.Key> =
        occurrences.fold(emptySet()) { result, selection -> result + selection.key }

    override fun forEach(action: (Selection) -> Unit) {
        occurrences.forEach(action)
    }

    override fun single(): Selection = occurrences.single()

    override fun single(predicate: (Selection) -> Boolean): Selection =
        occurrences.single(predicate)

    override fun plus(other: SelectionForest): SelectionForest =
        SelectionForestImpl(occurrences + (other as SelectionForestImpl).occurrences)
}
