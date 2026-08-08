package model

/**
 * A nominal type and unnormalized nested selections interpreted as one selection requirement.
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
 * Test-fixture field-resolver definitions use fragments while fixture preparation preserves source
 * guards and occurrence shape. Canonical registry assembly specializes the root to its concrete
 * resolver owner and exposes the result as an [GroundSelectionForest].
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
