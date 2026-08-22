package semantics.resolver26

import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.InputScalarValueRange
import semantics.arbitrary.ListTypeWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NodeObjectWeight
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.QueryScalarFieldWeight
import semantics.arbitrary.ResolverArgumentErrorWeight
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromObjectFieldPassiveUseWeight
import semantics.arbitrary.ResolverFromObjectFieldProviderArgumentVariableWeight
import semantics.arbitrary.ResolverFromObjectFieldProviderPathLength
import semantics.arbitrary.ResolverFromObjectFieldVariableOwnerLimit
import semantics.arbitrary.ResolverFromObjectFieldVariableOwnerUseWeight
import semantics.arbitrary.ResolverFromObjectFieldVariableUseDepth
import semantics.arbitrary.ResolverLiteralVariableConvergenceWeight
import semantics.arbitrary.ResolverNestedProviderPathWeight
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.ResolverVariablesOnNonQueryFieldsOnly
import semantics.arbitrary.RootQueryFieldCount
import semantics.arbitrary.SchemaObjectCount

// Defines orthogonal generated-world distributions for Resolver26's observable semantics.
internal enum class Resolver26BroadStressProfile(
    val id: String,
    val propertyProfile: String,
    val defaultSize: String,
    val requiredSignatures: Set<Resolver26StructuralSignature>,
    val config: Config,
) {
    BALANCED(
        id = "balanced",
        propertyProfile = "resolver26-broad-stress",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.STAMPED_RESOLVER_INSTANCE,
                Resolver26StructuralSignature.OBJECT_PATH_VARIABLE_OWNER,
                Resolver26StructuralSignature.MIXED_BINDING_SOURCES,
            ),
        config = balancedBroadConfig(),
    ),
    LOCALIZED_DESCENDANTS(
        id = "localized-descendants",
        propertyProfile = "resolver26-broad-localized-descendants",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.NESTED_VARIABLE_USE,
                Resolver26StructuralSignature.PASSIVE_DESCENDANT_VARIABLE_USE,
                Resolver26StructuralSignature.LIST_LOCALIZED_STAMP,
            ),
        config =
            balancedBroadConfig() +
                (ExplicitFieldResolverWeight to 0.5) +
                (ListTypeWeight to 0.65) +
                (ListValueSize to 1..2) +
                (ResolverVariableWeight to 0.9) +
                (ResolverFromObjectFieldPassiveUseWeight to 1.0) +
                (ResolverFromObjectFieldProviderArgumentVariableWeight to 1.0) +
                (ResolverFromObjectFieldVariableUseDepth to 2..4) +
                (ResolverVariablesOnNonQueryFieldsOnly to true),
    ),
    NULLABLE_ERRORS(
        id = "nullable-errors",
        propertyProfile = "resolver26-broad-nullable-errors",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.NESTED_PROVIDER_PATH,
                Resolver26StructuralSignature.NULL_PROVIDER_INTERMEDIATE,
                Resolver26StructuralSignature.ERROR_PROVIDER_INTERMEDIATE,
            ),
        config =
            balancedBroadConfig() +
                (NullableTypeWeight to 0.75) +
                (NullValueWeight to 0.45) +
                (ErrorValueWeight to 0.45) +
                (ResolverArgumentErrorWeight to 0.15) +
                (ResolverNestedProviderPathWeight to 1.0) +
                (ResolverFromObjectFieldProviderPathLength to 2..4),
    ),
    STAMP_COLLISIONS(
        id = "stamp-collisions",
        propertyProfile = "resolver26-broad-stamp-collisions",
        defaultSize = "10:20:50",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.EQUAL_STAMPED_ARGUMENTS,
            ),
        config =
            balancedBroadConfig() +
                (DuplicateSelectionWeight to 0.5) +
                (FieldArgumentWeight to 0.9) +
                (InputScalarValueRange to 0..1) +
                (ResolverVariableWeight to 0.95) +
                (ResolverVariableCount to 2..4) +
                (ResolverLiteralVariableConvergenceWeight to 0.75),
    ),
    MULTIPLE_OWNERS(
        id = "multiple-owners",
        propertyProfile = "resolver26-broad-multiple-owners",
        defaultSize = "10:50:20",
        requiredSignatures =
            setOf(
                Resolver26StructuralSignature.MULTIPLE_OBJECT_PATH_OWNERS,
                Resolver26StructuralSignature.OBJECT_PATH_OWNER_DEPENDENCY,
            ),
        config =
            balancedBroadConfig() +
                (RootQueryFieldCount to 6..8) +
                (ResolverVariableWeight to 0.9) +
                (ResolverVariableCount to 1..1) +
                (ResolverFromObjectFieldVariableOwnerLimit to 4) +
                (ResolverFromObjectFieldVariableOwnerUseWeight to 1.0),
    ),
    ;

    companion object {
        // Returns the profile named by either its command-line id or replay profile.
        fun fromConfigured(configured: String): Resolver26BroadStressProfile =
            entries.singleOrNull { profile ->
                configured == profile.id || configured == profile.propertyProfile
            } ?: error(
                "Unknown Resolver26 broad stress profile $configured; profiles=" +
                    entries.joinToString { profile -> profile.id },
            )
    }
}

// Enlarges world depth while bounding list fanout so a stress round remains finite and diagnosable.
internal fun Config.withLargeDeepResolver26Worlds(): Config =
    this +
        (MinimumSelectionDepth to 4) +
        (MaxSelectionDepth to 6) +
        (ListValueSize to 1..1) +
        (SchemaObjectCount to 8..12) +
        (ObjectFieldCount to 6..10) +
        (QueryFieldCount to 10..14) +
        (RootQueryFieldCount to 8..12) +
        (ResolverFragmentDepth to 5)

// Returns the broad baseline from which directed Resolver26 profiles apply pressure.
private fun balancedBroadConfig(): Config =
    Config.default +
        (MinimumSelectionDepth to 2) +
        (MaxSelectionDepth to 4) +
        (SchemaObjectCount to 5..7) +
        (ObjectFieldCount to 4..6) +
        (QueryFieldCount to 6..8) +
        (RootQueryFieldCount to 4..6) +
        (DuplicateSelectionWeight to 0.2) +
        (FieldArgumentWeight to 0.65) +
        (ExplicitFieldResolverWeight to 0.8) +
        (InputScalarValueRange to 0..4) +
        (ListTypeWeight to 0.25) +
        (ListValueSize to 0..2) +
        (NullableTypeWeight to 0.35) +
        (NullValueWeight to 0.15) +
        (ErrorValueWeight to 0.08) +
        (NodeObjectWeight to 0.2) +
        (QueryScalarFieldWeight to 0.2) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentWeight to 0.8) +
        (ResolverFragmentDepth to 3) +
        (ResolverArgumentErrorWeight to 0.05) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverVariablesEnabled to true) +
        (ResolverVariableWeight to 0.65) +
        (ResolverVariableCount to 1..3) +
        (ResolverLiteralVariableConvergenceWeight to 0.2) +
        (ResolverNestedProviderPathWeight to 0.5) +
        (ResolverFromObjectFieldProviderPathLength to 1..3) +
        (ResolverFromObjectFieldVariableUseDepth to 1..3) +
        (ResolverFromObjectFieldVariableOwnerLimit to 4) +
        (ResolverFromObjectFieldPassiveUseWeight to 0.25) +
        (ResolverFromObjectFieldVariableOwnerUseWeight to 0.25)
