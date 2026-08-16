package semantics.resolver03

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.registry.successorDemand
import semantics.SelectionCompletion
import semantics.RuntimeSupport
import semantics.orchestrateKeys

/**
 * Resolves [selections] with selective resolver applications. Whether the results contain only the
 * necessary OER nodes has not been proved.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): EngineResult.Object {
    require(world.selectiveResolvers) {
        "Resolver03 requires selective resolvers"
    }
    val source = world.resolverRegistry.resolveRootQuery()
    val runtimeSupport =
        RuntimeSupport { selections ->
            SelectionCompletion(
                selections = selections.successorDemand(),
            )
        }
    return context(runtimeSupport) {
        source.orchestrateKeys(
            path = emptyList(),
            selections = selections,
            resolved = EngineResult.Object.of(source.type, emptyMap(), mutable = true),
        )
    }
}
