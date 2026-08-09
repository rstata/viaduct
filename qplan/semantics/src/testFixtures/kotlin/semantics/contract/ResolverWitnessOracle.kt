package semantics.contract

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import semantics.arbitrary.ResolverApplicationIdentity
import semantics.arbitrary.RegisteredResolverOccurrence
import semantics.arbitrary.registeredResolverOccurrences
import semantics.arbitrary.resolutionFingerprint
import semantics.correctresolution.conformsToSelections
import semantics.materialize

/**
 * Expected deterministic resolver applications, independently reconstructed from OER cells.
 */
context(world: Assumptions)
fun EngineResult?.registeredResolverApplicationIdentityCounts():
    Map<ResolverApplicationIdentity, Int> =
    registeredResolverOccurrences(world.resolverRegistry)
        .map { cell ->
            val field =
                world.schema.objectField(
                    cell.canonicalField.typeName,
                    cell.canonicalField.fieldName,
                )
            val resolver = world.resolverRegistry.resolver(field)
            val fragment =
                resolver
                    .objectFragmentAt(
                        path = cell.occurrencePath,
                    )
            ResolverApplicationIdentity(
                key = cell.applicationKey,
                inputFingerprint =
                    runBlocking {
                        cell.containingObject
                            .materialize(fragment)
                            .resolutionFingerprint()
                    },
            )
        }.groupingBy { identity -> identity }
        .eachCount()

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
            val fragment =
                resolver
                    .objectFragmentAt(
                        path = cell.occurrencePath,
                    )
            !cell.containingObject.conformsToSelections(fragment)
        }
