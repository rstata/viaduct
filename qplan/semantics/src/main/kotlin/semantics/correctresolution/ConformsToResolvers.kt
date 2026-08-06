package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Schema
import model.Value
import semantics.instantiateVariables
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
    objectConformsToResolvers()

context(world: Assumptions)
private fun EngineResult.Object.objectConformsToResolvers(): Boolean {
    val registry = world.executorRegistry
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
                            .objectFragment(key.arguments)
                            .instantiateVariables(variableValues),
                    )
                val resolverValue = resolver.tenantResolve(input, key.arguments)
                value.engineResultConformsToResolverValue(resolverValue)
            }

        fieldResolverConforms && value.engineResultConformsToResolvers()
    }
}

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToResolvers(): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object -> objectConformsToResolvers()
        is EngineResult.List -> all { cell -> cell.value.engineResultConformsToResolvers() }
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
