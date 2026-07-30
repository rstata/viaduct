package model

import model.testing.GJSelectionParser

/** Parses one post-validation fragment as test-fixture preparation outside semantic model logic. */
fun Assumptions.selectionsFrom(fragment: String): Pair<Schema.CompositeType, SelectionForest> =
    GJSelectionParser(this).selectionsFrom(fragment)
