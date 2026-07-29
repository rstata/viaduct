package model

import model.spec.SpecSelection
import model.testing.GJSchema
import model.testing.GJSpecSelectionParser

/** Parses one post-validation fragment as test-fixture preparation outside semantic model logic. */
fun Assumptions.selectionsFrom(fragment: String): Pair<Schema.CompositeType, List<SpecSelection>> =
    GJSpecSelectionParser(schema as GJSchema, variableValues).selectionsFrom(fragment)
