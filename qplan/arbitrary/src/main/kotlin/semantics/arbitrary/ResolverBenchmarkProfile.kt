package semantics.arbitrary

/**
 * The shared selective resolver benchmark profile: node and field resolvers, object fragments,
 * FromArgument and FromObjectField variables, and no query fragments.
 */
fun resolverBenchmarkFullConfig(): Config =
    Config.default +
        (QueryFragmentsEnabled to false) +
        (MinimumSelectionDepth to 4) +
        (MaxSelectionDepth to 6) +
        (SchemaObjectCount to 4..5) +
        (ObjectFieldCount to 3..5) +
        (QueryFieldCount to 2..4) +
        (FieldArgumentWeight to 0.65) +
        (ExplicitFieldResolverWeight to 0.7) +
        (NullableTypeWeight to 0.15) +
        (NullValueWeight to 0.05) +
        (ErrorValueWeight to 0.02) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentWeight to 0.85) +
        (ResolverFragmentDepth to 3) +
        (NodeResolversEnabled to true) +
        (NodeObjectWeight to 0.05) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverVariablesEnabled to true)

/**
 * Generates large candidate schema/registry pairs for the fixed overhead corpus.
 */
fun resolverBenchmarkCorpusSearchConfig(): Config =
    Config.default +
        (QueryFragmentsEnabled to false) +
        (MinimumSelectionDepth to 8) +
        (MaxSelectionDepth to 10) +
        (SchemaObjectCount to 12..18) +
        (ObjectFieldCount to 8..14) +
        (QueryFieldCount to 12..18) +
        (RootQueryFieldCount to 4..6) +
        (NestedQueryFieldCount to 1..1) +
        (QueryScalarFieldWeight to 0.15) +
        (FieldArgumentWeight to 0.9) +
        (ExplicitFieldResolverWeight to 0.8) +
        (ListTypeWeight to 0.05) +
        (ListValueSize to 2..2) +
        (NullableTypeWeight to 0.2) +
        (NullValueWeight to 0.05) +
        (ErrorValueWeight to 0.02) +
        (DuplicateSelectionWeight to 0.25) +
        (AliasWeight to 0.25) +
        (ResolverFragmentsEnabled to true) +
        (ResolverFragmentWeight to 0.95) +
        (ResolverFragmentDepth to 8) +
        (NodeResolversEnabled to true) +
        (NodeObjectWeight to 0.3) +
        (ResolverFromArgumentVariablesEnabled to true) +
        (ResolverVariablesEnabled to true) +
        (ResolverVariableWeight to 0.95) +
        (ResolverVariableCount to 3..6) +
        (ResolverNestedProviderPathWeight to 0.9) +
        (ResolverFromObjectFieldProviderPathLength to 2..5) +
        (ResolverFromObjectFieldVariableUseDepth to 1..6) +
        (ResolverFromObjectFieldPassiveUseWeight to 0.25) +
        (ResolverFromObjectFieldVariableOwnerLimit to 12) +
        (ResolverFromObjectFieldVariableOwnerUseWeight to 1.0)

/**
 * Produces random overhead queries against the fixed corpus pair.
 */
fun resolverBenchmarkOverheadQueryConfig(): Config =
    resolverBenchmarkCorpusSearchConfig() +
        (MinimumSelectionDepth to 8) +
        (MaxSelectionDepth to 10) +
        (RootQueryFieldCount to 4..6)
