package model

/**
 * An unordered, post-validation field selection used for Viaduct field resolution.
 *
 * This is not a GraphQL AST selection or a description of response completion. Aliases, response
 * keys, source order, named fragments, inline-fragment nodes, and directives are absent. Inline
 * fragments have already been flattened into [nominalType] and [possibleTypes].
 *
 * A selection and its [subselections] form a finite, well-founded value. Kotlin `equals` is
 * currently undefined for [Selection]. The model does not yet assume that selections can or need
 * to be compared; proofs must not rely on selection equality until that requirement is established.
 */
interface Selection {
    /**
     * OER key being selected by this selection. (The field name of this key must be a field
     * of [nominalType].)
     *
     * Non-variable arguments values are in their coerced semantic form. An argument may
     * contain a [Schema.VariableValue] when the variable is unbound. Such keys use the
     * model's conservative equality: they merge only when they are definitely equal.
     *
     * Compared to GraphQL selections, field-resolver selections use the OER key rather than
     * response keys.
     */
    val key: ObjectEngineResult.Key

    /**
     * Provides context for [key]: this type together with the field-name in [key] give the
     * schema coordinate of the field intended by this selection.  However, it's [possibleTypes],
     * not the nominal type, that controls what types this selection actually applies to. This
     * object must be contained in [Assumptions.schema].
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
     * We maintain the invariant that this set is always a subset of the
     * object types contained by [nominalType]. All of these objects must be contained in
     * [Assumptions.schema].
     *
     * Compared to GraphQL selections, this is the intersection of all the possible types of all the
     * containing spreads and the type-level type of the containing document.  Unlike GraphQL
     * selection, this is the true intersection: even when a "broadening" type condition is
     * applied in the GraphQL selection, we won't broaden this corresponding type condition.
     */
    val possibleTypes: Set<Schema.ObjectType>

    /**
     * For composite types, the subselections on the object this selection selects.
     * Null when this selection selects a simple type, non-null otherwise, but may be
     * empty (unlike standard GraphQL).
     *
     * Because of GraphQL's validation rules for type conditions, the relationship
     * between the [nominalType] of a subselection and the the base type of the field
     * implied by the [nominalType] and [key.fieldName] coordinate is complicated.  Nested
     * type conditions nested spreads need only overlap pairwise:
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
    val subselections: List<Selection>?
}
