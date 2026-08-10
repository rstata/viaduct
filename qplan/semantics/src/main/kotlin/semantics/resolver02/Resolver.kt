package semantics.resolver02

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.registry.successorBoundaryDemand
import semantics.SelectionCompletion
import semantics.RuntimeSupport
import semantics.orchestrateKeys

/**
 * Resolves [selections] with non-selective resolver applications. Results may contain more OER
 * nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    require(!world.selectiveResolvers) {
        "Resolver02 requires non-selective resolvers"
    }
    val runtimeSupport =
        RuntimeSupport { selections ->
            SelectionCompletion(
                selections = selections.successorBoundaryDemand(),
            )
        }
    return context(runtimeSupport) {
        orchestrateKeys(
            path = emptyList(),
            selections = selections,
            resolved = EngineResult.Object.of(type, emptyMap(), mutable = true),
        )
    }
}
