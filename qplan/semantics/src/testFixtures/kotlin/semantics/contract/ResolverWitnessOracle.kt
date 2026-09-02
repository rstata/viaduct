package semantics.contract

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.registry.FieldResolver
import model.registry.ResolverFragment
import model.usedVariables
import semantics.ResolverSupport
import semantics.arbitrary.ResolverApplicationIdentity
import semantics.arbitrary.ResolverOccurrenceApplicationKey
import semantics.arbitrary.ResolverOccurrenceApplicationIdentity
import semantics.arbitrary.RegisteredResolverOccurrence
import semantics.arbitrary.forEachRegisteredResolverOccurrence
import semantics.arbitrary.resolutionFingerprint
import semantics.correctresolution.conformsToSelections
import semantics.correctresolution.conformsToSelectionsAt
import semantics.materialize

/**
 * Expected deterministic resolver applications reconstructed from every request-local Query root.
 *
 * The receiver is the primary result root; Query-fragment roots come from [Assumptions.queryValues].
 * This is independent of the observed application stream, but not of the completed results under
 * test: an extra result cell paired with an extra invocation can increase both counts together.
 */
context(world: Assumptions)
fun EngineResult?.registeredResolverApplicationIdentityCounts():
    Map<ResolverApplicationIdentity, Int> {
    val counts = linkedMapOf<ResolverApplicationIdentity, Int>()
    context(ResolverSupport.noCycleChecking { selections -> selections }) {
        requestQueryRoots().forEach { root ->
            root.forEachRegisteredResolverOccurrence(world.resolverRegistry) { cell ->
                val resolver = world.resolverRegistry.resolver(cell.field)
                val fragment =
                    resolver.objectFragmentSatisfiedBy(
                        root = root,
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
    }
    return counts
}

/** Expected deterministic applications qualified by their exact request-local Query root and path. */
context(world: Assumptions)
fun EngineResult?.registeredResolverOccurrenceApplicationIdentityCounts(): Map<
    ResolverOccurrenceApplicationIdentity,
    Int,
> =
    reconstructResolverOccurrenceApplicationIdentityCounts(null)

/**
 * Expected exact identities for the requested occurrences only.
 *
 * This supports sometimes-passive validation: a skipped standard resolver can retain unbound
 * object-fragment variables, while every actually observed application has complete bindings.
 */
context(world: Assumptions)
fun EngineResult?.registeredResolverOccurrenceApplicationIdentityCountsFor(
    includedOccurrences: Set<ResolverOccurrenceId>,
): Map<ResolverOccurrenceApplicationIdentity, Int> =
    reconstructResolverOccurrenceApplicationIdentityCounts(includedOccurrences)

context(world: Assumptions)
private fun EngineResult?.reconstructResolverOccurrenceApplicationIdentityCounts(
    includedOccurrences: Set<ResolverOccurrenceId>?,
): Map<ResolverOccurrenceApplicationIdentity, Int> {
    val counts = linkedMapOf<ResolverOccurrenceApplicationIdentity, Int>()
    context(ResolverSupport.noCycleChecking { selections -> selections }) {
        requestQueryRoots().forEach { root ->
            root.forEachRegisteredResolverOccurrence(world.resolverRegistry) { cell ->
                val resolverOccurrenceId = ResolverOccurrenceId.at(root, cell.occurrencePath)
                if (includedOccurrences != null && resolverOccurrenceId !in includedOccurrences) {
                    return@forEachRegisteredResolverOccurrence
                }
                val resolver = world.resolverRegistry.resolver(cell.field)
                val fragment =
                    resolver.objectFragmentSatisfiedBy(
                        root = root,
                        result = cell.containingObject,
                        path = cell.occurrencePath,
                    ) ?: error("Registered resolver occurrence has no complete object fragment")
                val identity =
                    ResolverOccurrenceApplicationIdentity(
                        resolverOccurrenceId =
                            resolverOccurrenceId,
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
    }
    return counts
}

/** Expected registered resolver occurrences without requiring their inputs to be materializable. */
context(world: Assumptions)
fun EngineResult?.registeredResolverOccurrenceApplicationKeyCounts():
    Map<ResolverOccurrenceApplicationKey, Int> {
    val counts = linkedMapOf<ResolverOccurrenceApplicationKey, Int>()
    requestQueryRoots().forEach { root ->
        root.forEachRegisteredResolverOccurrence(world.resolverRegistry) { cell ->
            counts.increment(
                ResolverOccurrenceApplicationKey(
                    resolverOccurrenceId = ResolverOccurrenceId.at(root, cell.occurrencePath),
                    applicationKey = cell.applicationKey,
                ),
            )
        }
    }
    return counts
}

context(world: Assumptions)
private fun EngineResult?.requestQueryRoots(): List<ObjectEngineResult> {
    val primaryRoot = this as? ObjectEngineResult ?: return emptyList()
    return buildList {
        add(primaryRoot)
        addAll(world.queryValues.values)
    }
}

context(world: Assumptions)
fun EngineResult?.unclosedRegisteredResolverOccurrences(): List<RegisteredResolverOccurrence> =
    buildList {
        val root = this@unclosedRegisteredResolverOccurrences as? ObjectEngineResult
            ?: return@buildList
        this@unclosedRegisteredResolverOccurrences
            .forEachRegisteredResolverOccurrence(world.resolverRegistry) { cell ->
                val resolver = world.resolverRegistry.resolver(cell.field)
                if (
                    resolver.objectFragmentSatisfiedBy(
                        root = root,
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
    root: ObjectEngineResult,
    result: ObjectEngineResult,
    path: List<PathComponent>,
): ResolverFragment? {
    val objectFragment = instantiateObjectFragmentAt(root, path)
    return objectFragment.takeIf {
        val constructionSelections = objectFragment.constructionSelections
        constructionSelections.usedVariables().all { variable ->
            variable.instanceId?.let(world::isBound) == true
        } &&
            result.conformsToSelectionsAt(
                selections = constructionSelections,
                path = path.dropLast(1),
            )
    }
}
