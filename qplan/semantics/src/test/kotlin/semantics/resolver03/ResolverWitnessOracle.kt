package semantics.resolver03

import model.Assumptions
import model.EngineResult
import semantics.arbitrary.ResolverApplicationIdentity
import semantics.arbitrary.registeredResolverCells
import semantics.arbitrary.resolutionFingerprint
import semantics.materialize

/**
 * Expected deterministic resolver applications, independently reconstructed from OER cells.
 */
context(world: Assumptions)
internal fun EngineResult?.registeredResolverApplicationIdentityCounts():
    Map<ResolverApplicationIdentity, Int> =
    registeredResolverCells(world.resolverRegistry)
        .map { cell ->
            val field =
                world.schema.objectField(
                    cell.canonicalField.typeName,
                    cell.canonicalField.fieldName,
                )
            val resolver = world.resolverRegistry.resolver(field)
            val fragment = resolver.objectFragment(cell.applicationKey.arguments)
            ResolverApplicationIdentity(
                key = cell.applicationKey,
                inputFingerprint =
                    cell.containingObject
                        .materialize(fragment)
                        .resolutionFingerprint(),
            )
        }.groupingBy { identity -> identity }
        .eachCount()
