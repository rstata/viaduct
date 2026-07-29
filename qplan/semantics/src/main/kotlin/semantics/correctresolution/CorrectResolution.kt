package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Fragment

/**
 * Whether this Query-rooted result is a correct field-resolution result for [fragment].
 *
 * The judgment is plan-independent and does not observe cell check components.  Also,
 * this judgment is purposefully permissive: as long as the [EngineResult.Object] conforms
 * to our world assumptions (e.g., regarding schema conformance and resolver conformance),
 * this predicate allows the [EngineResult.Object] to contain more cells than the input
 * [fragment] and implicated [model.registry.Resolver.Field.objectFragment]s require. Other predicates
 * define various degrees of minimality.
 *
 * This is math, not programming: the Kotlin application syntax here expresses the
 * modeled function relation, not programming-language procedure executions.  These should
 * be reasoned about as inductively-defined relations, not recursive routines.
 */
context(world: Assumptions)
fun EngineResult.Object.correctResolution(fragment: Fragment): Boolean =
    rootedAndWellTyped(fragment) &&         // Are both result and fragment rooted on the `Query` type?
        conformsToFragment(fragment) &&     // Does the result conform to the fragment?
        isClosedUnderResolverDemand() &&    // Have the RSSes of all necessary resolvers (transitively) been satisfied
        conformsToResolvers() &&            // Do the actual values conform to what the resolvers produce?
        conformsToTypename()                // Where the __typename field exists does it have the right value?
