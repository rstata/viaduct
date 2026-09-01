package semantics.contract

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Stamp
import model.registry.FieldResolver
import model.registry.ResolverObjectFragment
import model.usedVariables
import semantics.ResolverSupport
import semantics.arbitrary.ResolverApplicationIdentity
import semantics.arbitrary.ResolverOccurrenceApplicationIdentity
import semantics.arbitrary.RegisteredResolverOccurrence
import semantics.arbitrary.forEachRegisteredResolverOccurrence
import semantics.arbitrary.resolutionFingerprint
import semantics.correctresolution.conformsToSelections
import semantics.correctresolution.conformsToSelectionsAt
import semantics.materialize

/**
 * Expected deterministic resolver applications reconstructed from resolver-bearing result cells.
 *
 * This is independent of the observed application stream, but not of the completed result under
 * test: an extra result cell paired with an extra invocation can increase both counts together.
 */
context(world: Assumptions)
fun EngineResult?.registeredResolverApplicationIdentityCounts():
    Map<ResolverApplicationIdentity, Int> {
    val counts = linkedMapOf<ResolverApplicationIdentity, Int>()
    context(ResolverSupport.noCycleChecking { selections -> selections }) {
        forEachRegisteredResolverOccurrence(world.resolverRegistry) { cell ->
            val resolver = world.resolverRegistry.resolver(cell.field)
            val fragment =
                resolver.objectFragmentSatisfiedBy(
                    result = cell.containingObject,
                    path = cell.occurrencePath,
                ) ?: error("Registered resolver occurrence has no complete object fragment")
            val identity =
                ResolverApplicationIdentity(
                    key = cell.applicationKey,
                    inputFingerprint =
                        runBlocking {
                            cell.containingObject
                                .materialize(
                                    selections = fragment.materializeSelections,
                                    reader = cell.occurrencePath,
                                ).resolutionFingerprint()
                        },
                )
            counts.increment(identity)
        }
    }
    return counts
}

/** Expected deterministic resolver applications qualified by their exact result occurrence path. */
context(world: Assumptions)
fun EngineResult?.registeredResolverOccurrenceApplicationIdentityCounts():
    Map<ResolverOccurrenceApplicationIdentity, Int> {
    val counts = linkedMapOf<ResolverOccurrenceApplicationIdentity, Int>()
    context(ResolverSupport.noCycleChecking { selections -> selections }) {
        forEachRegisteredResolverOccurrence(world.resolverRegistry) { cell ->
            val resolver = world.resolverRegistry.resolver(cell.field)
            val fragment =
                resolver.objectFragmentSatisfiedBy(
                    result = cell.containingObject,
                    path = cell.occurrencePath,
                ) ?: error("Registered resolver occurrence has no complete object fragment")
            val identity =
                ResolverOccurrenceApplicationIdentity(
                    occurrencePath = cell.occurrencePath,
                    applicationIdentity =
                        ResolverApplicationIdentity(
                            key = cell.applicationKey,
                            inputFingerprint =
                                runBlocking {
                                    cell.containingObject
                                        .materialize(
                                            selections = fragment.materializeSelections,
                                            reader = cell.occurrencePath,
                                        ).resolutionFingerprint()
                                },
                        ),
                )
            counts.increment(identity)
        }
    }
    return counts
}

context(world: Assumptions)
fun EngineResult?.unclosedRegisteredResolverOccurrences(): List<RegisteredResolverOccurrence> =
    buildList {
        this@unclosedRegisteredResolverOccurrences
            .forEachRegisteredResolverOccurrence(world.resolverRegistry) { cell ->
                val resolver = world.resolverRegistry.resolver(cell.field)
                if (
                    resolver.objectFragmentSatisfiedBy(
                        result = cell.containingObject,
                        path = cell.occurrencePath,
                    ) == null
                ) {
                    add(cell)
                }
            }
    }

private fun <T> MutableMap<T, Int>.increment(key: T) {
    this[key] = getOrDefault(key, 0) + 1
}

context(world: Assumptions)
private fun FieldResolver.objectFragmentSatisfiedBy(
    result: ObjectEngineResult,
    path: List<PathComponent>,
): ResolverObjectFragment? {
    val objectFragment = instantiateObjectFragmentAt(path)
    return objectFragment.takeIf {
        val constructionSelections = objectFragment.constructionSelections
        constructionSelections.usedVariables().all { variable ->
            variable.isStamped && world.isBound(variable)
        } &&
            result.conformsToSelectionsAt(
                selections = constructionSelections,
                path = path.dropLast(1),
            )
    }
}
