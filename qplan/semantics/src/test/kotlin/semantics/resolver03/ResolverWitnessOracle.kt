package semantics.resolver03

import model.Assumptions
import model.EngineResult
import semantics.arbitrary.ResolverApplicationIdentity
import semantics.arbitrary.registeredResolverCells
import semantics.arbitrary.resolutionFingerprint
import semantics.instantiateVariables
import semantics.materialize

/**
 * Expected deterministic resolver applications, independently reconstructed from OER cells.
 */
context(world: Assumptions)
internal fun EngineResult?.registeredResolverApplicationIdentityCounts():
    Map<ResolverApplicationIdentity, Int> =
    registeredResolverCells(world.executorRegistry)
        .map { cell ->
            val field =
                world.schema.field(
                    cell.canonicalField.typeName,
                    cell.canonicalField.fieldName,
                )
            val resolver = world.executorRegistry.resolver(field)
            val fragment =
                resolver
                    .objectFragment(cell.applicationKey.arguments)
                    .instantiateVariables(cell.containingObject.variableValues)
            ResolverApplicationIdentity(
                key = cell.applicationKey,
                inputFingerprint =
                    cell.containingObject
                        .materialize(fragment)
                        .resolutionFingerprint(),
            )
        }.groupingBy { identity -> identity }
        .eachCount()
