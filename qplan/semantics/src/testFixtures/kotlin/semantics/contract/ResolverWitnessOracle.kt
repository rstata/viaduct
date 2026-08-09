package semantics.contract

import model.Assumptions
import model.EngineResult
import semantics.arbitrary.ResolverApplicationIdentity
import semantics.arbitrary.RegisteredResolverCell
import semantics.arbitrary.registeredResolverCells
import semantics.arbitrary.resolutionFingerprint
import semantics.correctresolution.conformsToSelections
import semantics.materialize

/**
 * Expected deterministic resolver applications, independently reconstructed from OER cells.
 */
context(world: Assumptions)
fun EngineResult?.registeredResolverApplicationIdentityCounts():
    Map<ResolverApplicationIdentity, Int> =
    registeredResolverCells(world.resolverRegistry)
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
                    cell.containingObject
                        .materialize(fragment)
                        .resolutionFingerprint(),
            )
        }.groupingBy { identity -> identity }
        .eachCount()

context(world: Assumptions)
fun EngineResult?.unclosedRegisteredResolverCells(): List<RegisteredResolverCell> =
    registeredResolverCells(world.resolverRegistry)
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
