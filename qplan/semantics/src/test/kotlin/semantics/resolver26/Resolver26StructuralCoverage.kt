package semantics.resolver26

import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Value
import semantics.arbitrary.ArbitraryRegistry
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.RegisteredResolverOccurrence
import semantics.arbitrary.ResolutionWitness
import semantics.arbitrary.ResolverApplicationRecord

// Names result-visible structures that distinguish Resolver26's stamped execution cases.
internal enum class Resolver26StructuralSignature {
    STAMPED_RESOLVER_INSTANCE,
    LIST_LOCALIZED_STAMP,
    EQUAL_STAMPED_ARGUMENTS,
    OBJECT_PATH_VARIABLE_OWNER,
    NESTED_VARIABLE_USE,
    PASSIVE_DESCENDANT_VARIABLE_USE,
    MIXED_BINDING_SOURCES,
    NESTED_PROVIDER_PATH,
    NULL_PROVIDER_INTERMEDIATE,
    ERROR_PROVIDER_INTERMEDIATE,
    MULTIPLE_OBJECT_PATH_OWNERS,
    OBJECT_PATH_OWNER_DEPENDENCY,
}

// Classifies one completed case without consulting Resolver26's scheduler or private runtime state.
internal fun resolver26StructuralSignatures(
    occurrences: List<RegisteredResolverOccurrence>,
    witness: ResolutionWitness,
    registry: ArbitraryRegistry,
): Set<Resolver26StructuralSignature> {
    val signatures: MutableSet<Resolver26StructuralSignature> = linkedSetOf()
    val stampedOccurrences: List<RegisteredResolverOccurrence> =
        occurrences.filter { occurrence ->
            occurrence.groundKey().selectionStamp != null
        }
    val activeSourceFields: Set<FieldCoordinate> =
        witness.applications
            .mapTo(linkedSetOf()) { application ->
                registry.sourceResolverCoordinate(application.key.field)
            }
    val activeObjectPathOwners: Set<FieldCoordinate> =
        activeSourceFields.intersect(registry.fromObjectFieldVariableOwnerFields)

    if (stampedOccurrences.isNotEmpty()) {
        signatures += Resolver26StructuralSignature.STAMPED_RESOLVER_INSTANCE
    }
    if (
        stampedOccurrences.any { occurrence ->
            requireNotNull(occurrence.groundKey().selectionStamp).resolverPath.any { component ->
                component is ListEngineResult.Index
            }
        }
    ) {
        signatures += Resolver26StructuralSignature.LIST_LOCALIZED_STAMP
    }
    if (
        stampedOccurrences
            .groupBy { occurrence ->
    VisibleResolverOccurrence(
                    containingObjectPath = occurrence.occurrencePath.dropLast(1),
                    field = occurrence.applicationKey.field,
                    arguments = occurrence.applicationKey.arguments,
                )
            }.values
            .any { equalVisibleOccurrences ->
                equalVisibleOccurrences
                    .map { occurrence ->
                        requireNotNull(occurrence.groundKey().selectionStamp)
                    }.toSet()
                    .size > 1
            }
    ) {
        signatures += Resolver26StructuralSignature.EQUAL_STAMPED_ARGUMENTS
    }
    if (activeObjectPathOwners.isNotEmpty()) {
        signatures += Resolver26StructuralSignature.OBJECT_PATH_VARIABLE_OWNER
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.nestedFromObjectFieldVariableUseOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.NESTED_VARIABLE_USE
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.passiveTopLevelFromObjectFieldVariableUseOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.PASSIVE_DESCENDANT_VARIABLE_USE
    }
    if (
        witness.applications.any(registry::applicationUsesFromArgumentVariable) &&
        witness.applications.any(registry::applicationUsesFromObjectFieldVariable)
    ) {
        signatures += Resolver26StructuralSignature.MIXED_BINDING_SOURCES
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.nestedFromObjectFieldVariableOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.NESTED_PROVIDER_PATH
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.nullIntermediateFromObjectFieldVariableOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.NULL_PROVIDER_INTERMEDIATE
    }
    if (
        activeSourceFields.any { sourceField ->
            sourceField in registry.errorIntermediateFromObjectFieldVariableOwnerFields
        }
    ) {
        signatures += Resolver26StructuralSignature.ERROR_PROVIDER_INTERMEDIATE
    }
    if (activeObjectPathOwners.size > 1) {
        signatures += Resolver26StructuralSignature.MULTIPLE_OBJECT_PATH_OWNERS
    }
    if (
        registry.fromObjectFieldVariableOwnerDependencies.any { (reader, author) ->
            reader in activeSourceFields && author in activeSourceFields
        }
    ) {
        signatures += Resolver26StructuralSignature.OBJECT_PATH_OWNER_DEPENDENCY
    }
    return signatures
}

// Describes the GraphQL-visible identity shared by stamped occurrences before stamp comparison.
private data class VisibleResolverOccurrence(
    val containingObjectPath: List<PathComponent>,
    val field: FieldCoordinate,
    val arguments: Value.Arguments,
)

// Returns the exact stored key at this registered resolver occurrence.
private fun RegisteredResolverOccurrence.groundKey(): ObjectEngineResult.GroundKey =
    occurrencePath.last() as ObjectEngineResult.GroundKey

// Reports whether this recorded application belongs to a FromArgument variable owner.
private fun ArbitraryRegistry.applicationUsesFromArgumentVariable(
    application: ResolverApplicationRecord,
): Boolean =
    sourceResolverCoordinate(application.key.field) in fromArgumentVariableOwnerFields

// Reports whether this recorded application belongs to a FromObjectField variable owner.
private fun ArbitraryRegistry.applicationUsesFromObjectFieldVariable(
    application: ResolverApplicationRecord,
): Boolean =
    sourceResolverCoordinate(application.key.field) in fromObjectFieldVariableOwnerFields
