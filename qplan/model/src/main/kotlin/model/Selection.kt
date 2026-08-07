package model

/**
 * A free commutative collection of opaque [Selection] members.
 *
 * ### Member Count
 *
 * [size] observes the number of current members. A forest returned directly by
 * [model.spec.flatten] has one member for each flattened GraphQL field occurrence, but that is a
 * postcondition of flattening rather than an invariant of every forest. In particular, [merge]
 * replaces members with equal concrete coordinates under current variable bindings by one
 * normalized member, which may represent several source occurrences.
 *
 * ### Equality And Observation
 *
 * Selection and forest equality are undefined, and no operation internally compares whole
 * [Selection] values or exposes member order. [keys] and [groupBy] may compare and deduplicate
 * explicitly projected values without changing the forest's members. [merge] is the explicit
 * normalization boundary that compares coordinates under current bindings and coalesces forest
 * members.
 */
sealed interface SelectionForest {
    val size: Int

    fun isEmpty(): Boolean

    fun all(predicate: (Selection) -> Boolean): Boolean

    fun filter(predicate: (Selection) -> Boolean): SelectionForest

    fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest

    /** The structural keys contributed independently by all occurrences. */
    fun keys(): Set<Value.Key>

    fun <K> groupBy(keySelector: (Selection) -> K): Map<K, SelectionForest>

    fun forEach(action: (Selection) -> Unit)

    fun single(): Selection

    operator fun plus(other: SelectionForest): SelectionForest
}

/**
 * A normalized selection forest specialized to one concrete parent object type.
 *
 * ### Invariant: object-selection-forest-normalization
 *
 * Every selection is an [ObjectSelection] whose key field belongs to [type], whose
 * `possibleTypes == setOf(type)`, and whose key occurs exactly once.
 */
sealed interface ObjectSelectionForest : SelectionForest {
    val type: Schema.ObjectType

    override fun filter(predicate: (Selection) -> Boolean): ObjectSelectionForest

    override fun keys(): Set<Value.ObjectKey>

    /** Returns the unique selection for each key. */
    fun byKey(): Map<Value.ObjectKey, ObjectSelection>

    /**
     * Returns the selection for [key].
     *
     * @throws NoSuchElementException when [key] is absent
     */
    operator fun get(key: Value.ObjectKey): ObjectSelection

    companion object {
        /** Constructs a normalized forest satisfying [ObjectSelectionForest]'s invariant. */
        fun of(
            type: Schema.ObjectType,
            selections: Iterable<ObjectSelection>,
        ): ObjectSelectionForest {
            val occurrences = selections.toList()
            require(
                occurrences.all { selection ->
                    selection.key.field.containingType == type &&
                        selection.possibleTypes == setOf(type)
                },
            ) {
                "Object selections must belong exclusively to ${type.typeName}"
            }
            val byKey = occurrences.associateBy(ObjectSelection::key)
            require(byKey.size == occurrences.size) {
                "Object selection keys must be unique"
            }
            return ObjectSelectionForestImpl(type, byKey)
        }
    }
}

/** Constructs a [SelectionForest] containing the supplied occurrences. */
fun selectionForestOf(vararg selections: Selection): SelectionForest =
    SelectionForestImpl(selections.asList())

/** Constructs a [SelectionForest] from these occurrences. */
fun Iterable<Selection>.toSelectionForest(): SelectionForest =
    SelectionForestImpl(toList())

/**
 * Returns the demand applicable to concrete parent [type], normalized under current bindings.
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
 * Every currently bound stamped variable is replaced by its binding before the result key is
 * constructed. Unbound stamped variables and templates remain symbolic, so the result may still
 * contain [Value.Variable] values and must be merged again after additional bindings are added.
 */
context(world: Assumptions)
fun SelectionForest.merge(type: Schema.ObjectType): ObjectSelectionForest {
    val selections =
        filter { selection -> type in selection.possibleTypes }
            .groupBy { selection ->
                selection.objectKey(type).substituteBindings()
            }.entries
            .map { (key, selections) ->
                ObjectSelection.of(
                    key = key,
                    possibleTypes = setOf(type),
                    subselections =
                        selections.flatMap { selection -> selection.subselections },
                )
            }
    return ObjectSelectionForest.of(type, selections)
}

/**
 * Specializes this selection's field coordinate to concrete parent [type].
 *
 * This operation preserves arguments while applying the argument definition, including defaults,
 * of the corresponding canonical object field.
 */
fun Selection.objectKey(type: Schema.ObjectType): Value.ObjectKey {
    val concreteField = type.fields.getValue(key.field.fieldName)
    val concreteKey = key as? Value.ObjectKey
    if (concreteKey?.field == concreteField) return concreteKey
    return Value.ObjectKey.of(
        field = concreteField,
        arguments = key.arguments.fieldValues,
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
 * - A selection's [key] is a [Value.ObjectKey] exactly when it is an [ObjectSelection].
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
     * Exact alias-free output-field coordinate being selected.
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
     * Compared to GraphQL selections, model selections use an alias-free schema field coordinate
     * rather than a GraphQL response key.
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
            validateSelection(key, possibleTypes, subselections)
            return when (key) {
                is Value.ObjectKey ->
                    ObjectSelectionImpl(
                        key = key,
                        possibleTypes = possibleTypes,
                        subselections = subselections,
                    )
                else ->
                    SelectionImpl(
                        key = key,
                        possibleTypes = possibleTypes,
                        subselections = subselections,
                    )
            }
        }

        /** Constructs the precise selection category for a concrete-object field key. */
        fun of(
            key: Value.ObjectKey,
            possibleTypes: Set<Schema.ObjectType>,
            subselections: SelectionForest,
        ): ObjectSelection = ObjectSelection.of(key, possibleTypes, subselections)
    }
}

/** A selection whose field coordinate belongs to a concrete object type. */
sealed interface ObjectSelection : Selection {
    override val key: Value.ObjectKey

    companion object {
        fun of(
            key: Value.ObjectKey,
            possibleTypes: Set<Schema.ObjectType>,
            subselections: SelectionForest,
        ): ObjectSelection {
            validateSelection(key, possibleTypes, subselections)
            return ObjectSelectionImpl(key, possibleTypes, subselections)
        }
    }
}

private fun validateSelection(
    key: Value.Key,
    possibleTypes: Set<Schema.ObjectType>,
    subselections: SelectionForest,
) {
    val fieldOwner = key.field.containingType
    require(possibleTypes.all { it in fieldOwner.possibleTypes }) {
        "Selection possible types must be contained by ${fieldOwner.typeName}"
    }
    require(
        key.field.typeExpr.baseType is Schema.CompositeType || subselections.isEmpty(),
    ) {
        "Leaf selection ${fieldOwner.typeName}.${key.field.fieldName} has subselections"
    }
}

private class SelectionImpl(
    override val key: Value.Key,
    override val possibleTypes: Set<Schema.ObjectType>,
    override val subselections: SelectionForest,
) : Selection

private class ObjectSelectionImpl(
    override val key: Value.ObjectKey,
    override val possibleTypes: Set<Schema.ObjectType>,
    override val subselections: SelectionForest,
) : ObjectSelection

private abstract class AbstractSelectionForest(
    val occurrences: List<Selection>,
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
                transform(selection).occurrences()
            },
        )

    override fun <K> groupBy(keySelector: (Selection) -> K): Map<K, SelectionForest> =
        occurrences
            .groupBy(keySelector)
            .mapValues { (_, selections) -> SelectionForestImpl(selections) }

    override fun keys(): Set<Value.Key> =
        occurrences.fold(emptySet()) { result, selection -> result + selection.key }

    override fun forEach(action: (Selection) -> Unit) {
        occurrences.forEach(action)
    }

    override fun single(): Selection = occurrences.single()

    override fun plus(other: SelectionForest): SelectionForest =
        SelectionForestImpl(occurrences + other.occurrences())
}

private class SelectionForestImpl(
    occurrences: List<Selection>,
) : AbstractSelectionForest(occurrences)

private class ObjectSelectionForestImpl(
    override val type: Schema.ObjectType,
    private val selectionsByKey: Map<Value.ObjectKey, ObjectSelection>,
) : AbstractSelectionForest(selectionsByKey.values.toList()),
    ObjectSelectionForest {
    override fun filter(predicate: (Selection) -> Boolean): ObjectSelectionForest =
        ObjectSelectionForestImpl(
            type,
            selectionsByKey.filterValues(predicate),
        )

    override fun keys(): Set<Value.ObjectKey> = selectionsByKey.keys

    override fun byKey(): Map<Value.ObjectKey, ObjectSelection> = selectionsByKey

    override fun get(key: Value.ObjectKey): ObjectSelection = selectionsByKey.getValue(key)
}

private fun SelectionForest.occurrences(): List<Selection> =
    (this as AbstractSelectionForest).occurrences
