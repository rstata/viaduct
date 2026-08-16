package semantics.resolver02

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.registry.successorBoundaryDemand
import semantics.RuntimeSupport
import semantics.orchestrateKeys

/**
 * Resolves [selections] with non-selective resolver applications. Results may contain more OER
 * nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): EngineResult.Object {
    require(!world.selectiveResolvers) {
        "Resolver02 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.resolveRootQuery()
    val runtimeSupport =
        RuntimeSupport { selections ->
            selections.successorBoundaryDemand()
        }
    return context(runtimeSupport) {
        source.orchestrateKeys(
            path = emptyList(),
            selections = selections,
            resolved = EngineResult.Object.of(source.type, emptyMap(), mutable = true),
        )
    }
}
