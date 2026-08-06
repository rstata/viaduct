package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ObjectSelectionForest

/**
 * Whether this Query-rooted result is a correct field-resolution result for [selections].
 *
 * The judgment is plan-independent and does not observe cell check components.  Also,
 * this judgment is purposefully permissive: as long as the [EngineResult.Object] conforms
 * to our world assumptions (e.g., regarding schema conformance and resolver conformance),
 * this predicate allows the [EngineResult.Object] to contain more cells than the input
 * [selections] and implicated [model.registry.FieldResolver.objectFragment]s require. Other
 * predicates define various degrees of minimality.
 *
 * [selections] must be rooted at the reasoning world's canonical Query type.
 *
 * This is math, not programming: the Kotlin application syntax here expresses the
 * modeled function relation, not programming-language procedure executions.  These should
 * be reasoned about as inductively-defined relations, not recursive routines.
 */
context(world: Assumptions)
fun EngineResult.Object.correctResolution(selections: ObjectSelectionForest): Boolean {
    require(selections.type == world.schema.query) {
        "Correct-resolution selections must be rooted at Query"
    }
    return rootedAndWellTyped() &&          // Is the result rooted on the `Query` type?
        conformsToSelections(selections) && // Does the result conform to the selections?
        isClosedUnderResolverDemand() &&    // Have the RSSes of all necessary resolvers (transitively) been satisfied
        conformsToVariables() &&            // Do stored variables equal their field-relative provider values?
        conformsToResolvers() &&            // Do the actual values conform to what the resolvers produce?
        conformsToTypename()                // Where the __typename field exists does it have the right value?
}
