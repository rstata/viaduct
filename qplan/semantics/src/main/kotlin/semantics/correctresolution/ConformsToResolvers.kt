package semantics.correctresolution

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.Schema
import model.Value
import model.applicableGroundSelections
import model.usedVariables
import model.registry.FieldResolver
import semantics.RuntimeSupport
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
 * value is present. It observes values but never field or type checks.
 */
context(world: Assumptions)
fun EngineResult.Object.conformsToResolvers(): Boolean =
    context(RuntimeSupport.noCycleChecking()) {
        objectConformsToResolvers(emptyList())
    }

context(world: Assumptions, runtimeSupport: RuntimeSupport)
private fun EngineResult.Object.objectConformsToResolvers(
    path: List<PathComponent>,
): Boolean {
    val registry = world.resolverRegistry
    return keys.all { groundKey ->
        val value = getValue(groundKey).get()
        val fieldResolverConforms =
            if (
                groundKey.arguments.argumentsContainErrorValue() ||
                groundKey.field !in registry
            ) {
                true
            } else {
                val resolver = registry.resolver(groundKey.field)
                val coordinate = path + groundKey
                val objectFragment =
                    resolver.objectFragmentSatisfiedBy(
                        result = this,
                        path = coordinate,
                    ) ?: return false
                val input =
                    runBlocking {
                        materialize(
                            selections = objectFragment,
                            reader = coordinate,
                        )
                    }
                val resolverArguments =
                    Value.Arguments.of(
                        field = groundKey.field,
                        fields = groundKey.arguments.fieldValues,
                    )
                val resolverValue = resolver(input, resolverArguments)
                value.engineResultConformsToResolverValue(resolverValue)
            }

        fieldResolverConforms && value.engineResultConformsToResolvers(path + groundKey)
    }
}

context(world: Assumptions)
private fun FieldResolver.objectFragmentSatisfiedBy(
    result: EngineResult.Object,
    path: List<PathComponent>,
): ObjectSelectionForest? {
    val groundKey = path.lastOrNull() as? Value.GroundKey
    val selectionStamped =
        if (groundKey is Value.GroundKey.Stamped) {
            stampFrom(groundKey.selectionStamp)
        } else {
            stamp(path)
        }
    if (
        selectionStamped.usedVariables().all { variable ->
            variable is Value.Variable.Stamped && world.isBound(variable)
        }
    ) {
        val fullyStamped = selectionStamped.applicableGroundSelections(field.containingType)
        return fullyStamped.takeIf { demand ->
            result.conformsToSelectionsAt(demand, path.dropLast(1))
        }
    }

    val variableStamped = objectFragmentAt(path)
    return variableStamped.takeIf { demand ->
        result.conformsToSelectionsAt(demand, path.dropLast(1))
    }
}

context(world: Assumptions, runtimeSupport: RuntimeSupport)
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
            indices.all { index ->
                get(index).engineResultConformsToResolvers(
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
                    get(index).engineResultConformsToResolverValue(
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

    return keys.all { groundKey ->
        if (!fieldBelongsToResolver(groundKey.field)) {
            true
        } else if (!resolverValue.fieldValues.containsKey(groundKey)) {
            false
        } else {
            getValue(groundKey).get().engineResultConformsToResolverValue(
                resolverValue.fieldValues.getValue(groundKey),
            )
        }
    }
}
