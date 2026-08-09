package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.PathComponent
import model.Value

/**
 * Whether every resolver activated by this result has its required input in the same result tree.
 *
 * A present field value activates its registered field resolver unless its arguments contain an
 * error. The containing object must then conform to that resolver's object fragment. Recursing
 * through every present value makes these requirements transitive while preserving the type guards
 * interpreted by [conformsToSelections].
 *
 * This predicate observes value presence and content, but never field or type checks.
 */
context(world: Assumptions)
fun EngineResult.Object.isClosedUnderResolverDemand(): Boolean =
    objectIsClosedUnderResolverDemand(emptyList())

context(world: Assumptions)
private fun EngineResult.Object.objectIsClosedUnderResolverDemand(
    path: List<PathComponent>,
): Boolean {
    val registry = world.resolverRegistry

    return keys.all { key ->
        val fieldResolverDemandIsClosed =
            key.arguments.argumentsContainErrorValue() ||
                key.field !in registry ||
                conformsToSelections(
                    registry
                        .resolver(key.field)
                        .objectFragmentAt(path + key),
                )

        fieldResolverDemandIsClosed &&
            getValue(key).get().engineResultIsClosedUnderResolverDemand(path + key)
    }
}

context(world: Assumptions)
private fun EngineResult?.engineResultIsClosedUnderResolverDemand(
    path: List<PathComponent>,
): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object -> objectIsClosedUnderResolverDemand(path)
        is EngineResult.List ->
            indices.all { index ->
                get(index).engineResultIsClosedUnderResolverDemand(
                    path + Value.ListIndex.of(index),
                )
            }
    }

internal fun Value.Arguments.argumentsContainErrorValue(): Boolean =
    fieldValues.values.any { value -> value.inputValueContainsErrorValue() }

private fun Value.Input?.inputValueContainsErrorValue(): Boolean =
    when {
        this == Value.Error -> true
        this is Value.InputList ->
            values.any { element -> element.inputValueContainsErrorValue() }

        this is Value.InputObject ->
            fieldValues.values.any { value -> value.inputValueContainsErrorValue() }

        else -> false
    }
