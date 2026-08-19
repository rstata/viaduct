package semantics.resolver01

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.SelectionForest
import semantics.RuntimeSupport
import semantics.orchestrateKeys

/**
 * Resolves [selections] when resolver object fragments are empty, except for generated
 * `T_V_A_Bridge.node` fragments that select passive sibling `id`. Results are non-selective and may
 * contain more OER nodes than are strictly necessary to resolve the query.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult {
    require(!world.selectiveResolvers) {
        "Resolver01 requires non-selective resolvers"
    }
    val source = world.resolverRegistry.resolveRootQuery()
    val runtimeSupport =
        RuntimeSupport { selections -> selections }
    return context(runtimeSupport) {
        source.orchestrateKeys(
            path = emptyList(),
            selections = selections,
            resolved = ObjectEngineResult.of(source.schemaType, emptyMap(), mutable = true),
        )
    }
}
