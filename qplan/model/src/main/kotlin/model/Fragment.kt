package model

/**
 * A nominal type and nested selections.
 *
 * A fragment and its [subselections] form a finite, well-founded value. The subselections may be
 * empty.
 */
interface Fragment {
    /** The nominal type carried by this fragment. */
    val nominalType: Schema.CompositeType

    /** The selections nested within this fragment. */
    val subselections: List<Selection>
}
