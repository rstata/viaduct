package semantics.resolver25

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromObjectFieldVariableOwnerLimit
import semantics.arbitrary.ResolverNestedProviderPathWeight
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesOnQueryFieldsOnly
import semantics.arbitrary.SchemaObjectCount
import semantics.contract.DeepResolverStressContract

class ResolverStressTest : DeepResolverStressContract {
    override val resolverName: String = "resolver25"
    override val objectPathVariablesEnabled: Boolean = true
    override val nodeResolversEnabled: Boolean = false
    override val nestedObjectPathCoverageRequired: Boolean = true
    override val minimumActivatedObjectPathResolverChainLength: Int = 3
    override val stressConfigOverrides: Config =
        Config.default +
            (SchemaObjectCount to 5..7) +
            (ObjectFieldCount to 4..6) +
            (QueryFieldCount to 6..8) +
            (FieldArgumentWeight to 0.5) +
            (ExplicitFieldResolverWeight to 0.9) +
            (NullableTypeWeight to 0.0) +
            (NullValueWeight to 0.0) +
            (ErrorValueWeight to 0.0) +
            (ResolverFragmentWeight to 1.0) +
            (ResolverFragmentDepth to 1) +
            (ResolverFromArgumentVariablesEnabled to false) +
            (ResolverVariableWeight to 0.9) +
            (ResolverVariableCount to 2..4) +
            (ResolverNestedProviderPathWeight to 0.9) +
            (ResolverFromObjectFieldVariableOwnerLimit to 1) +
            (ResolverVariablesOnQueryFieldsOnly to true)

    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        context(world) {
            root.resolve(selections)
        }
}
