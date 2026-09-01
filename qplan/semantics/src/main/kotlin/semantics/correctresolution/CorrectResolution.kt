package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.requireQueryTypeDef

/**
 * Whether this primary Query-rooted result is a correct field-resolution result for [selections].
 *
 * The judgment is plan-independent and does not observe access-acceptance results. Also,
 * this judgment is purposefully permissive: as long as the [ObjectEngineResult] conforms
 * to our world assumptions (e.g., regarding schema conformance and resolver conformance),
 * this predicate allows the [ObjectEngineResult] to contain more values than the input
 * [selections] and implicated [model.registry.FieldResolver.objectFragment]s require. Other
 * predicates define various degrees of minimality.
 *
 * [selections] must be rooted at the reasoning world's canonical Query type. Reapplying a resolver
 * with a nonempty query fragment also requires the independently resolved Query OER retained in
 * [Assumptions.queryValues] for that exact resolver occurrence to be a correct resolution.
 *
 * This is math, not programming: the Kotlin application syntax here expresses the
 * modeled function relation, not programming-language procedure executions.  These should
 * be reasoned about as inductively-defined relations, not recursive routines.
 */
context(world: Assumptions)
fun ObjectEngineResult.correctResolution(
    selections: ObjectSelectionForest,
): Boolean {
    require(selections.type == world.schema.requireQueryTypeDef()) {
        "Correct-resolution selections must be rooted at Query"
    }
    val resolverApplicationCache = ResolverApplicationCache(this)
    return rootedAndWellTyped() &&          // Is the result rooted on the `Query` type?
        conformsToSelections(selections) && // Does the result conform to the selections?
        isClosedUnderResolverDemand(resolverApplicationCache) && // Have the RSSes of all necessary resolvers (transitively) been satisfied
        conformsToResolvers(resolverApplicationCache) // Do the actual values conform to what the resolvers produce?
}
