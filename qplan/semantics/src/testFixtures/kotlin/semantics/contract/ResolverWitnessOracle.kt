package semantics.contract

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.Value
import model.applicableGroundSelections
import model.usedVariables
import model.registry.FieldResolver
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
                                    selections = fragment,
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
                                            selections = fragment,
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
    result: EngineResult.Object,
    path: List<PathComponent>,
): ObjectSelectionForest? {
    val groundKey = path.lastOrNull() as? Value.GroundKey
    val selectionStamped =
        if (groundKey is Value.GroundKey.Stamped) {
            stampFrom(groundKey.selectionStamp)
        } else {
            stamp(path)
        }
    if (
        selectionStamped.usedVariables().all { variable ->
            variable is Value.Variable.Stamped && world.isBound(variable)
        }
    ) {
        val fullyStamped = selectionStamped.applicableGroundSelections(field.containingType)
        return fullyStamped.takeIf { demand ->
            result.conformsToSelectionsAt(demand, path.dropLast(1))
        }
    }

    val variableStamped = objectFragmentAt(path)
    return variableStamped.takeIf { demand ->
        result.conformsToSelectionsAt(demand, path.dropLast(1))
    }
}
