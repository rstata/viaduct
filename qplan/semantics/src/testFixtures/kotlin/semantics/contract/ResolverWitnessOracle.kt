package semantics.contract

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Stamp
import model.Value
import model.applicableGroundSelections
import model.registry.FieldResolver
import model.registry.ResolverObjectFragment
import model.usedVariables
import semantics.RuntimeSupport
import semantics.arbitrary.ResolverApplicationIdentity
import semantics.arbitrary.ResolverOccurrenceApplicationIdentity
import semantics.arbitrary.RegisteredResolverOccurrence
import semantics.arbitrary.registeredResolverOccurrences
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
    Map<ResolverApplicationIdentity, Int> =
    context(RuntimeSupport.noCycleChecking()) {
        registeredResolverOccurrences(world.resolverRegistry)
            .map { cell ->
                val field =
                    world.schema.objectField(
                        cell.canonicalField.typeName,
                        cell.canonicalField.fieldName,
                    )
                val resolver = world.resolverRegistry.resolver(field)
                val fragment =
                    resolver.objectFragmentSatisfiedBy(
                        result = cell.containingObject,
                        path = cell.occurrencePath,
                    ) ?: error("Registered resolver occurrence has no complete object fragment")
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
            }.groupingBy { identity -> identity }
            .eachCount()
    }

/** Expected deterministic resolver applications qualified by their exact result occurrence path. */
context(world: Assumptions)
fun EngineResult?.registeredResolverOccurrenceApplicationIdentityCounts():
    Map<ResolverOccurrenceApplicationIdentity, Int> =
    context(RuntimeSupport.noCycleChecking()) {
        registeredResolverOccurrences(world.resolverRegistry)
            .map { cell ->
                val field =
                    world.schema.objectField(
                        cell.canonicalField.typeName,
                        cell.canonicalField.fieldName,
                    )
                val resolver = world.resolverRegistry.resolver(field)
                val fragment =
                    resolver.objectFragmentSatisfiedBy(
                        result = cell.containingObject,
                        path = cell.occurrencePath,
                    ) ?: error("Registered resolver occurrence has no complete object fragment")
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
            }.groupingBy { identity -> identity }
            .eachCount()
    }

context(world: Assumptions)
fun EngineResult?.unclosedRegisteredResolverOccurrences(): List<RegisteredResolverOccurrence> =
    registeredResolverOccurrences(world.resolverRegistry)
        .filter { cell ->
            val field =
                world.schema.objectField(
                    cell.canonicalField.typeName,
                    cell.canonicalField.fieldName,
                )
            val resolver = world.resolverRegistry.resolver(field)
            resolver.objectFragmentSatisfiedBy(
                result = cell.containingObject,
                path = cell.occurrencePath,
            ) == null
        }

context(world: Assumptions)
private fun FieldResolver.objectFragmentSatisfiedBy(
    result: ObjectEngineResult,
    path: List<PathComponent>,
): ResolverObjectFragment? {
    val groundKey = path.lastOrNull() as? ObjectEngineResult.GroundKey
    val selectionStamp = groundKey?.stamp as? Stamp.Occurrence
    val candidates =
        if (selectionStamp != null) {
            listOf(instantiateObjectFragment(selectionStamp))
        } else {
            listOf(
                instantiateObjectFragment(Stamp.Occurrence.of(resolverPath = path)),
                instantiateObjectFragmentAt(path),
            )
        }
    return candidates.firstOrNull { objectFragment ->
        val constructionSelections = objectFragment.constructionSelections
        constructionSelections.usedVariables().all { variable ->
            variable.isStamped && world.isBound(variable)
        } &&
            result.conformsToSelectionsAt(
                selections =
                    constructionSelections.applicableGroundSelections(field.containingType),
                path = path.dropLast(1),
            )
    }
}
