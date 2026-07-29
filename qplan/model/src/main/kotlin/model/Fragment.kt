package model

/**
 * A nominal type and nested selections required as one object-valued resolver input.
 *
 * ### Invariant: fragment-selection-context
 *
 * [subselections] are the flattened field occurrences of a post-validation selection set
 * interpreted with [nominalType].
 *
 * ### Invariant: fragment-well-foundedness
 *
 * A fragment and its [subselections] form a finite, well-founded value. The subselections may be
 * empty.
 *
 * ### Interpretation
 *
 * Resolving this fragment produces the selection-independent object supplied to a
 * [model.registry.FieldResolverFunction].
 */
sealed interface Fragment {
    /** The nominal type carried by this fragment. */
    val nominalType: Schema.CompositeType

    /** The selections nested within this fragment. */
    val subselections: SelectionForest

    companion object {
        fun of(
            nominalType: Schema.CompositeType,
            subselections: SelectionForest,
        ): Fragment = FragmentImpl(nominalType, subselections)
    }
}

private class FragmentImpl(
    override val nominalType: Schema.CompositeType,
    override val subselections: SelectionForest,
) : Fragment
