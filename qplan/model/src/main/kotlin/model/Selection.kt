package model

/**
 * A free commutative collection of opaque [Selection] members.
 *
 * ### Member Count
 *
 * [size] observes the number of current members. A forest returned directly by
 * [model.spec.flatten] has one member for each flattened GraphQL field occurrence, but that is a
 * postcondition of flattening rather than an invariant of every forest.
 *
 * ### Equality And Observation
 *
 * Selection and forest equality are undefined, and no operation compares whole [Selection] values
 * or exposes member order.
 */
sealed interface SelectionForest {
    val size: Int

    fun isEmpty(): Boolean

    fun all(predicate: (Selection) -> Boolean): Boolean

    fun filter(predicate: (Selection) -> Boolean): SelectionForest

    fun flatMap(transform: (Selection) -> SelectionForest): SelectionForest

    fun forEach(action: (Selection) -> Unit)

    fun single(): Selection

    operator fun plus(other: SelectionForest): SelectionForest
}

/**
 * A normalized ground selection forest specialized to one concrete parent object type.
 *
 * ### Invariant: ground-selection-forest-normalization
 *
 * Every selection is a [GroundSelection] whose key field belongs to [type], whose
 * `possibleTypes == setOf(type)`, and whose key occurs exactly once.
 */
sealed interface GroundSelectionForest : SelectionForest {
    val type: Schema.ObjectType

    override fun filter(predicate: (Selection) -> Boolean): GroundSelectionForest

    /** The ground keys contributed by these normalized selections. */
    fun keys(): Set<Value.GroundKey>

    /** Returns the unique selection for each key. */
    fun byKey(): Map<Value.GroundKey, GroundSelection>

    /**
     * Returns the selection for [key].
     *
     * @throws NoSuchElementException when [key] is absent
     */
    operator fun get(key: Value.GroundKey): GroundSelection

    companion object {
        /** Constructs a normalized forest satisfying [GroundSelectionForest]'s invariant. */
        fun of(
            type: Schema.ObjectType,
            selections: Iterable<GroundSelection>,
        ): GroundSelectionForest {
            val occurrences = selections.toList()
            require(
                occurrences.all { selection ->
                    selection.key.field.containingType == type &&
                        selection.possibleTypes == setOf(type)
                },
            ) {
                "Object selections must belong exclusively to ${type.typeName}"
            }
            val byKey = occurrences.associateBy(GroundSelection::key)
            require(byKey.size == occurrences.size) {
                "Object selection keys must be unique"
            }
            return GroundSelectionForestImpl(type, byKey)
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
 * Returns the occurrences applicable to concrete parent [type], specialized to that type.
 *
 * Applicability is tested before specialization, so an inapplicable occurrence contributes neither
 * its key nor its descendants. Each applicable key is reconstructed against the canonical field
 * on [type], including concrete argument defaults. Occurrences remain distinct and arguments
 * remain open. Every result selection has `possibleTypes == setOf(type)`. Subselections are not
 * recursively specialized because their concrete runtime parent type is not yet known.
 */
fun SelectionForest.merge(type: Schema.ObjectType): SelectionForest =
    flatMap { selection ->
        if (type !in selection.possibleTypes) {
            selectionForestOf()
        } else {
            selectionForestOf(
                Selection.of(
                    key = selection.specializedKey(type),
                    possibleTypes = setOf(type),
                    subselections = selection.subselections,
                ),
            )
        }
    }

/**
 * Grounds and normalizes the demand applicable to concrete parent [type].
 *
 * Applicable occurrences are specialized, every stamped variable is replaced by its binding, and
 * equal resulting [Value.GroundKey] coordinates are coalesced by concatenating their
 * subselections.
 *
 * @throws IllegalStateException when an applicable key contains an unbound stamped variable or an
 * unstamped template
 */
context(world: Assumptions)
fun SelectionForest.mergeToGround(type: Schema.ObjectType): GroundSelectionForest {
    val selections =
        merge(type)
            .occurrences()
            .groupBy { selection ->
                Value.GroundKey.of(
                    field = selection.key.field as Schema.ObjectField,
                    arguments = selection.key.arguments.instantiateBindings(),
                )
            }.map { (key, occurrences) ->
                GroundSelection.of(
                    key = key,
                    possibleTypes = setOf(type),
                    subselections =
                        occurrences
                            .map { selection -> selection.subselections }
                            .fold(selectionForestOf()) { forest, children ->
                                forest + children
                            },
                )
            }
    return GroundSelectionForest.of(type, selections)
}

/**
 * Specializes this selection's field coordinate to concrete parent [type].
 *
 * This operation preserves arguments while applying the argument definition, including defaults,
 * of the corresponding canonical object field.
 */
fun Selection.specializedKey(type: Schema.ObjectType): Value.Key {
    val concreteField = type.fields.getValue(key.field.fieldName)
    if (key.field == concreteField) return key
    return Value.Key.of(
        field = concreteField,
        arguments =
            OpenArguments.of(
                concreteField,
                key.arguments.fieldExpressions(),
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
 * - A selection's [key] is a [Value.GroundKey] exactly when it is an [GroundSelection].
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
                is Value.GroundKey ->
                    GroundSelectionImpl(
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
            key: Value.GroundKey,
            possibleTypes: Set<Schema.ObjectType>,
            subselections: SelectionForest,
        ): GroundSelection = GroundSelection.of(key, possibleTypes, subselections)
    }
}

/** A selection whose field coordinate and arguments are ground. */
sealed interface GroundSelection : Selection {
    override val key: Value.GroundKey

    companion object {
        fun of(
            key: Value.GroundKey,
            possibleTypes: Set<Schema.ObjectType>,
            subselections: SelectionForest,
        ): GroundSelection {
            validateSelection(key, possibleTypes, subselections)
            return GroundSelectionImpl(key, possibleTypes, subselections)
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

private class GroundSelectionImpl(
    override val key: Value.GroundKey,
    override val possibleTypes: Set<Schema.ObjectType>,
    override val subselections: SelectionForest,
) : GroundSelection

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

private class GroundSelectionForestImpl(
    override val type: Schema.ObjectType,
    private val selectionsByKey: Map<Value.GroundKey, GroundSelection>,
) : AbstractSelectionForest(selectionsByKey.values.toList()),
    GroundSelectionForest {
    override fun filter(predicate: (Selection) -> Boolean): GroundSelectionForest =
        GroundSelectionForestImpl(
            type,
            selectionsByKey.filterValues(predicate),
        )

    override fun keys(): Set<Value.GroundKey> = selectionsByKey.keys

    override fun byKey(): Map<Value.GroundKey, GroundSelection> = selectionsByKey

    override fun get(key: Value.GroundKey): GroundSelection = selectionsByKey.getValue(key)
}

private fun SelectionForest.occurrences(): List<Selection> =
    (this as AbstractSelectionForest).occurrences
