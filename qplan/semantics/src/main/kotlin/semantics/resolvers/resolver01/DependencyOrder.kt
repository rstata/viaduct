package semantics.resolvers.resolver01

import model.ObjectEngineResult
import model.PathComponent
import semantics.correctresolution.argumentsContainErrorValue
import semantics.shared.SharedOperationContext

/** Returns a topological ordering of [keys] using Kahn's algorithm, demand first. */
context(operation: SharedOperationContext<*>)
internal fun dependencyOrder(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    keys: Set<ObjectEngineResult.GroundKey>,
    ordered: List<ObjectEngineResult.GroundKey> = emptyList(),
): List<ObjectEngineResult.GroundKey> {
    if (keys.isEmpty()) return ordered

    val ready =
        keys.filter { key ->
            dependenciesOf(root, path, key, keys).isEmpty()
        }.toSet()
    require(ready.isNotEmpty()) {
        "Resolver dependencies on ${keys.first().field.containingDef.name} contain a cycle"
    }
    return dependencyOrder(
        root = root,
        path = path,
        keys = keys - ready,
        ordered = ordered + ready,
    )
}

/** Returns the unresolved sibling keys demanded by the field resolver for [consumer]. */
context(operation: SharedOperationContext<*>)
private fun dependenciesOf(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    consumer: ObjectEngineResult.GroundKey,
    unresolved: Set<ObjectEngineResult.GroundKey>,
): Set<ObjectEngineResult.GroundKey> {
    if (consumer.arguments.argumentsContainErrorValue()) {
        return emptySet()
    }
    require(consumer.field in operation.world.resolverRegistry) {
        "Demanded field ${consumer.field.containingDef.name}/${consumer.field.name} is absent from its source " +
            "and has no registered resolver"
    }

    return unresolved
        .filter { sibling ->
            sibling != consumer &&
                consumer.demandsFromSibling(sibling, root, path + consumer)
        }.toSet()
}
