package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.PathComponent
import model.Schema
import model.Value
import model.instantiateBindings
import model.merge
import semantics.materialize

/**
 * Whether every activated resolver agrees with the values attributed to it in this result tree.
 *
 * Field resolvers receive the containing object materialized according to their object fragment.
 * Each OER value is compared as a positional subset of the resolver's complete finite output.
 * Comparison preserves list positions and stops before coordinates supplied by another resolver or
 * by the engine. The complete OER tree is still traversed, so those coordinates are checked by
 * their own resolvers.
 *
 * This predicate assumes [isClosedUnderResolverDemand] has established that every resolver input
 * cell is present. It observes cell values but never cell check components.
 */
context(world: Assumptions)
fun EngineResult.Object.conformsToResolvers(): Boolean =
    objectConformsToResolvers(emptyList())

context(world: Assumptions)
private fun EngineResult.Object.objectConformsToResolvers(
    path: List<PathComponent>,
): Boolean {
    val registry = world.resolverRegistry
    return keys.all { key ->
        val value = fetch(key).value
        val fieldResolverConforms =
            if (
                key.arguments.argumentsContainErrorValue() ||
                key.field !in registry
            ) {
                true
            } else {
                val resolver = registry.resolver(key.field)
                val input =
                    materialize(
                        resolver
                            .stampedObjectFragment(path + key)
                            .merge(type)
                            .instantiateBindings(),
                    )
                val resolverValue = resolver(input, key.arguments)
                value.engineResultConformsToResolverValue(resolverValue)
            }

        fieldResolverConforms && value.engineResultConformsToResolvers(path + key)
    }
}

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToResolvers(
    path: List<PathComponent>,
): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object -> objectConformsToResolvers(path)
        is EngineResult.List ->
            withIndex().all { (index, cell) ->
                cell.value.engineResultConformsToResolvers(
                    path + Value.ListIndex.of(index),
                )
            }
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToResolverValue(
    resolverValue: Value.Output?,
): Boolean =
    when (this) {
        null -> resolverValue == null
        Value.Error -> resolverValue == Value.Error
        is Value.Simple -> this == resolverValue

        is EngineResult.Object ->
            resolverValue != Value.Error &&
                resolverValue is Value.Object &&
                objectFieldsConformToResolverValue(
                    resolverValue = resolverValue,
                    fieldBelongsToResolver = { field -> !world.behavioral(field) },
                )

        is EngineResult.List ->
            resolverValue != Value.Error &&
            resolverValue is Value.OutputList &&
                size == resolverValue.values.size &&
                indices.all { index ->
                    get(index).value.engineResultConformsToResolverValue(
                        resolverValue.values[index],
                    )
                }
    }

context(world: Assumptions)
private fun EngineResult.Object.objectFieldsConformToResolverValue(
    resolverValue: Value.Object,
    fieldBelongsToResolver: (Schema.ObjectField) -> Boolean,
): Boolean {
    if (type != resolverValue.type) return false

    return keys.all { key ->
        if (!fieldBelongsToResolver(key.field)) {
            true
        } else if (!resolverValue.fieldValues.containsKey(key)) {
            false
        } else {
            fetch(key).value.engineResultConformsToResolverValue(
                resolverValue.fieldValues.getValue(key),
            )
        }
    }
}
