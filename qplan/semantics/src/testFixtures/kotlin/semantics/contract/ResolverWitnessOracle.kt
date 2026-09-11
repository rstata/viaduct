package semantics.contract

import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineResult
import model.engineObjectDataOf
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.registry.FieldResolver
import model.registry.ResolverFragment
import model.usedVariables
import semantics.shared.CycleCheckState
import semantics.arbitrary.ResolverApplicationIdentity
import semantics.arbitrary.ResolverApplicationKey
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolverOccurrenceApplicationKey
import semantics.arbitrary.ResolverOccurrenceApplicationIdentity
import semantics.arbitrary.resolutionFingerprint
import semantics.correctresolution.conformsToSelectionsAt
import semantics.correctresolution.ownedRootFieldReferenceInvocations
import semantics.shared.materialize
import semantics.shared.groundedArguments
import semantics.shared.OperationContext
import semantics.shared.ResolverObservations
import semantics.shared.RootFieldReferenceInvocationObservation

/**
 * Expected deterministic resolver applications reconstructed from every request-local Query root.
 *
 * The receiver is the primary result root; Query-fragment roots come from resolver observations.
 * Ordinary occurrences are independent of the observed application stream. Symbolic-reference
 * occurrences necessarily use invocation observations for their fresh roots and paths, but only
 * after source-ownership replay justifies each observed hop from the completed results under test.
 */
context(operation: OperationContext)
fun EngineResult?.registeredResolverApplicationIdentityCounts():
    Map<ResolverApplicationIdentity, Int> {
    val counts = linkedMapOf<ResolverApplicationIdentity, Int>()
    context(operation, CycleCheckState.createNOP()) {
        fun record(
            root: ObjectEngineResult,
            cell: RegisteredResolverOccurrence,
        ) {
            val resolver = operation.resolverRegistry.resolver(cell.field)
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
        requestQueryRoots().forEach { root ->
            root.forEachRegisteredResolverOccurrence(operation.resolverRegistry) { cell -> record(root, cell) }
        }
        rootFieldReferenceOccurrences().forEach { occurrence ->
            counts.increment(
                ResolverApplicationIdentity(
                    key = occurrence.applicationKey(),
                    inputFingerprint =
                        engineObjectDataOf(occurrence.invocationKey.field.containingDef)
                            .resolutionFingerprint(),
                ),
            )
        }
    }
    return counts
}

/** Expected deterministic applications qualified by their exact request-local Query root and path. */
context(operation: OperationContext)
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
context(operation: OperationContext)
fun EngineResult?.registeredResolverOccurrenceApplicationIdentityCountsFor(
    includedOccurrences: Set<ResolverOccurrenceId>,
): Map<ResolverOccurrenceApplicationIdentity, Int> =
    reconstructResolverOccurrenceApplicationIdentityCounts(includedOccurrences)

context(operation: OperationContext)
private fun EngineResult?.reconstructResolverOccurrenceApplicationIdentityCounts(
    includedOccurrences: Set<ResolverOccurrenceId>?,
): Map<ResolverOccurrenceApplicationIdentity, Int> {
    val counts = linkedMapOf<ResolverOccurrenceApplicationIdentity, Int>()
    context(operation, CycleCheckState.createNOP()) {
        fun record(
            root: ObjectEngineResult,
            cell: RegisteredResolverOccurrence,
        ) {
            val resolverOccurrenceId = ResolverOccurrenceId.at(root, cell.occurrencePath)
            if (includedOccurrences != null && resolverOccurrenceId !in includedOccurrences) return
            val resolver = operation.resolverRegistry.resolver(cell.field)
            val fragment =
                resolver.objectFragmentSatisfiedBy(
                    root = root,
                    result = cell.containingObject,
                    path = cell.occurrencePath,
                ) ?: error("Registered resolver occurrence has no complete object fragment")
            val identity =
                ResolverOccurrenceApplicationIdentity(
                    resolverOccurrenceId = resolverOccurrenceId,
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
        requestQueryRoots().forEach { root ->
            root.forEachRegisteredResolverOccurrence(operation.resolverRegistry) { cell ->
                record(root, cell)
            }
        }
        rootFieldReferenceOccurrences().forEach { occurrence ->
            val resolverOccurrenceId =
                ResolverOccurrenceId.at(occurrence.invocationRoot, occurrence.invocationPath)
            if (includedOccurrences == null || resolverOccurrenceId in includedOccurrences) {
                counts.increment(
                    ResolverOccurrenceApplicationIdentity(
                        resolverOccurrenceId = resolverOccurrenceId,
                        applicationIdentity =
                            ResolverApplicationIdentity(
                                key = occurrence.applicationKey(),
                                inputFingerprint =
                                    engineObjectDataOf(occurrence.invocationKey.field.containingDef)
                                        .resolutionFingerprint(),
                            ),
                    ),
                )
            }
        }
    }
    return counts
}

/** Expected registered resolver occurrences without requiring their inputs to be materializable. */
context(operation: OperationContext)
fun EngineResult?.registeredResolverOccurrenceApplicationKeyCounts():
    Map<ResolverOccurrenceApplicationKey, Int> {
    val counts = linkedMapOf<ResolverOccurrenceApplicationKey, Int>()
    requestQueryRoots().forEach { root ->
        root.forEachRegisteredResolverOccurrence(operation.resolverRegistry) { cell ->
            counts.increment(
                ResolverOccurrenceApplicationKey(
                    resolverOccurrenceId = ResolverOccurrenceId.at(root, cell.occurrencePath),
                    applicationKey = cell.applicationKey,
                ),
            )
        }
    }
    rootFieldReferenceOccurrences().forEach { occurrence ->
        counts.increment(
            ResolverOccurrenceApplicationKey(
                resolverOccurrenceId =
                    ResolverOccurrenceId.at(
                        occurrence.invocationRoot,
                        occurrence.invocationPath,
                    ),
                applicationKey = occurrence.applicationKey(),
            ),
        )
    }
    return counts
}

context(operation: OperationContext)
private fun EngineResult?.requestQueryRoots(): List<ObjectEngineResult> {
    val primaryRoot = this as? ObjectEngineResult ?: return emptyList()
    return buildList {
        add(primaryRoot)
        addAll(
            operation.resolverObservations()
                .allQueryFragmentResults()
                .values
                .flatten(),
        )
    }
}

context(operation: OperationContext)
private fun EngineResult?.rootFieldReferenceOccurrences(): List<
    RootFieldReferenceInvocationObservation,
> =
    (this as? ObjectEngineResult)?.ownedRootFieldReferenceInvocations().orEmpty()

context(operation: OperationContext)
private fun RootFieldReferenceInvocationObservation.applicationKey(): ResolverApplicationKey =
    ResolverApplicationKey(
        field =
            FieldCoordinate(
                invocationKey.field.containingDef.name,
                invocationKey.field.name,
            ),
        arguments = invocationKey.groundedArguments() as Arguments.Resolved,
    )

context(operation: OperationContext)
fun EngineResult?.unclosedRegisteredResolverOccurrences(): List<RegisteredResolverOccurrence> =
    buildList {
        fun recordIfUnclosed(
            root: ObjectEngineResult,
            cell: RegisteredResolverOccurrence,
        ) {
            val resolver = operation.resolverRegistry.resolver(cell.field)
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
        requestQueryRoots().forEach { root ->
            root.forEachRegisteredResolverOccurrence(operation.resolverRegistry) { cell ->
                recordIfUnclosed(root, cell)
            }
        }
        // Reference targets have no object fragment, so their input is closed by construction.
    }

private fun <T> MutableMap<T, Int>.increment(key: T) {
    this[key] = getOrDefault(key, 0) + 1
}

context(operation: OperationContext)
private fun FieldResolver.objectFragmentSatisfiedBy(
    root: ObjectEngineResult,
    result: ObjectEngineResult,
    path: List<PathComponent>,
): ResolverFragment? {
    val objectFragment = instantiateFragmentsAt(root, path).objectFragment
    return objectFragment.takeIf {
        val constructionSelections = objectFragment.constructionSelections
        constructionSelections.usedVariables().all { variable ->
            operation.variableBindingsState.isBound(variable.instanceId!!)
        } &&
            result.conformsToSelectionsAt(
                selections = constructionSelections,
                path = path.dropLast(1),
            )
    }
}

private fun OperationContext.resolverObservations(): ResolverObservations =
    resolverObserver as? ResolverObservations
        ?: error("Resolver observations were not recorded for this operation")
