package model

/**
 * A nominal type and nested selections required as one object-valued resolver input.
 *
 * A fragment and its [subselections] form a finite, well-founded value. The subselections may be
 * empty. Resolving this fragment produces the selection-independent object supplied to a
 * [model.registry.FieldResolverFunction].
 */
interface Fragment {
    /** The nominal type carried by this fragment. */
    val nominalType: Schema.CompositeType

    /** The selections nested within this fragment. */
    val subselections: SelectionForest
}
