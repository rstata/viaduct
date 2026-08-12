package semantics.resolver25

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ResolverFromObjectFieldVariableOwnerLimit
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverVariablesOnQueryFieldsOnly
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.MixedVariableGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import semantics.contract.ObjectFragmentFromObjectPathGeneratedResolverContract
import semantics.contract.ObjectFragmentGeneratedResolverContract

class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    ObjectFragmentFromObjectPathGeneratedResolverContract,
    MixedVariableGeneratedResolverContract,
    FeatureInteractionGeneratedResolverContract {
    override val selectiveResolvers: Boolean
        get() = true

    override val objectPathGeneratorConfigOverrides: Config =
        Config.default +
            (ErrorValueWeight to 0.0) +
            (NullableTypeWeight to 0.0) +
            (ResolverFromObjectFieldVariableOwnerLimit to 1) +
            (ResolverFragmentDepth to 1) +
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
