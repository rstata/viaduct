package semantics.resolver03

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import model.registry.successorDemand
import semantics.ResolverSupport
import semantics.orchestrateKeys
import model.schemaType

/**
 * Resolves [selections] with selective resolver applications. Whether the results contain only the
 * necessary OER nodes has not been proved.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver03 requires selective resolvers"
    }
    val source = world.resolverRegistry.createRootQueryInput()
    val resolverSupport =
        ResolverSupport.noCycleChecking { selections ->
            selections.successorDemand()
        }
    return context(resolverSupport) {
        source.orchestrateKeys(
            path = emptyList(),
            selections = selections,
            resolved = ObjectEngineResult.of(source.schemaType, emptyMap(), mutable = true),
        )
    }
}
