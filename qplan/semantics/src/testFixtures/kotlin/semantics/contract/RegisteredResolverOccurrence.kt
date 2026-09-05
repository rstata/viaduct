package semantics.contract

import model.Arguments
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.registry.ResolverRegistry
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolutionWitnessBoundExceededException
import semantics.arbitrary.ResolutionWitnessBounds
import semantics.arbitrary.ResolverApplicationKey
import semantics.arbitrary.resolutionFingerprint
import semantics.shared.groundedArguments
import semantics.shared.isContextuallyGrounded
import semantics.shared.OperationContext

/** One registered resolver occurrence discovered independently in a completed result tree. */
data class RegisteredResolverOccurrence(
    val applicationKey: ResolverApplicationKey,
    val field: viaduct.graphql.schema.ViaductSchema.ObjectField,
    val occurrencePath: List<PathComponent>,
    val containingObject: ObjectEngineResult,
) {
    val canonicalField: FieldCoordinate
        get() = applicationKey.field
}

context(operation: OperationContext)
fun EngineResult?.registeredResolverOccurrences(
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): List<RegisteredResolverOccurrence> {
    val occurrences = mutableListOf<RegisteredResolverOccurrence>()
    visitRegisteredResolverOccurrences(
        registry = registry,
        bounds = bounds,
        canonicalOrder = true,
        visitOccurrence = occurrences::add,
    )
    return occurrences
}

context(operation: OperationContext)
fun EngineResult?.forEachRegisteredResolverOccurrence(
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
    visitOccurrence: (RegisteredResolverOccurrence) -> Unit,
) {
    visitRegisteredResolverOccurrences(registry, bounds, canonicalOrder = false, visitOccurrence)
}

context(operation: OperationContext)
private fun EngineResult?.visitRegisteredResolverOccurrences(
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds,
    canonicalOrder: Boolean,
    visitOccurrence: (RegisteredResolverOccurrence) -> Unit,
) {
    var visitedNodes = 0

    fun visit(
        value: EngineResult?,
        path: List<PathComponent>,
    ) {
        visitedNodes += 1
        if (visitedNodes > bounds.maxResultNodes) {
            throw ResolutionWitnessBoundExceededException("result-node", bounds.maxResultNodes)
        }
        if (value == null || value is ErrorEngineResult) return

        when (value) {
            is ObjectEngineResult -> {
                val keys =
                    if (canonicalOrder) {
                        value.keys.sortedBy { key -> key.canonicalFingerprint(bounds) }
                    } else {
                        value.keys
                    }
                keys.forEach { key ->
                    val fieldPath = path + key
                    require(key.isContextuallyGrounded()) {
                        "Resolver occurrence key is not contextually grounded: $key"
                    }
                    val arguments = key.groundedArguments() as? Arguments.Resolved
                    if (key.field in registry && arguments != null) {
                        visitOccurrence(
                            RegisteredResolverOccurrence(
                                applicationKey =
                                    ResolverApplicationKey(
                                        field =
                                            FieldCoordinate(
                                                key.field.containingDef.name,
                                                key.field.name,
                                            ),
                                        arguments = arguments,
                                    ),
                                field = key.field,
                                occurrencePath = fieldPath,
                                containingObject = value,
                            ),
                        )
                    }
                    if (key !is ObjectEngineResult.ParentKey) {
                        visit(value.getCell(key).getValue().get(), fieldPath)
                    }
                }
            }

            is ListEngineResult ->
                value.forEachIndexed { index, cell ->
                    visit(cell.getValue().get(), path + ListEngineResult.Index.of(index))
                }

            else -> Unit
        }
    }

    visit(this, emptyList())
}

context(operation: OperationContext)
fun EngineResult?.registeredResolverOccurrenceCounts(
    registry: ResolverRegistry,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): Map<ResolverApplicationKey, Int> {
    val counts = linkedMapOf<ResolverApplicationKey, Int>()
    forEachRegisteredResolverOccurrence(registry, bounds) { occurrence ->
        counts[occurrence.applicationKey] =
            counts.getOrDefault(occurrence.applicationKey, 0) + 1
    }
    return counts
}

private fun ObjectEngineResult.ObjectKey.canonicalFingerprint(
    bounds: ResolutionWitnessBounds,
): String =
    "${field.containingDef.name.length}:${field.containingDef.name}/" +
        "${field.name.length}:${field.name};" +
        arguments.resolutionFingerprint(field, bounds).value
