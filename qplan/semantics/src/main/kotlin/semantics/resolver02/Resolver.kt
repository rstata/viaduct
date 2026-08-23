package semantics.resolver02

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.registry.successorBoundaryDemand
import semantics.ResolverSupport
import semantics.orchestrateKeys
import model.schemaType

/**
 * Resolves [selections] with non-selective resolver applications. Results may contain more OER
 * nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver02 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val resolverSupport =
        ResolverSupport.noCycleChecking { selections ->
            selections.successorBoundaryDemand()
        }
    return context(resolverSupport) {
        source.orchestrateKeys(
            path = emptyList(),
            selections = selections,
            resolved = ObjectEngineResult.of(source.schemaType, emptyMap(), mutable = true),
        )
    }
}
