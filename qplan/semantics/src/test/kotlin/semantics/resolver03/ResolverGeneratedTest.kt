package semantics.resolver03

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.EmptyObjectFragmentGeneratedResolverContract
import semantics.contract.FeatureInteractionGeneratedResolverContract
import semantics.contract.NodeGeneratedResolverContract
import semantics.contract.ObjectFragmentFromArgumentGeneratedResolverContract
import semantics.contract.ObjectFragmentGeneratedResolverContract

/**
 * Ordinary generated-world acceptance. Resolver03-specific trace, mutation, depth, and stress
 * claims remain in their dedicated suites.
 */
class ResolverGeneratedTest :
    EmptyObjectFragmentGeneratedResolverContract,
    NodeGeneratedResolverContract,
    ObjectFragmentGeneratedResolverContract,
    ObjectFragmentFromArgumentGeneratedResolverContract,
    FeatureInteractionGeneratedResolverContract {
    override fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object =
        context(world) {
            root.resolve(selections)
        }
}
