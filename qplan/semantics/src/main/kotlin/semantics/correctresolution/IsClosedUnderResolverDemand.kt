package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Value

/**
 * Whether every resolver activated by this result has its required input in the same result tree.
 *
 * A present field cell activates its registered field resolver unless its arguments contain an
 * error. The containing object must then conform to that resolver's object fragment. Recursing
 * through every present value makes these requirements transitive while preserving the type guards
 * interpreted by [conformsToSelections].
 *
 * This predicate observes cell presence and values, but never cell check components.
 */
context(world: Assumptions)
fun EngineResult.Object.isClosedUnderResolverDemand(): Boolean =
    objectIsClosedUnderResolverDemand()

context(world: Assumptions)
private fun EngineResult.Object.objectIsClosedUnderResolverDemand(): Boolean {
    val registry = world.resolverRegistry

    return keys.all { key ->
        val fieldResolverDemandIsClosed =
            key.arguments.argumentsContainErrorValue() ||
                key.field !in registry ||
                conformsToSelections(
                    registry
                        .resolver(key.field)
                        .objectFragment(key.arguments),
                )

        fieldResolverDemandIsClosed &&
            fetch(key).value.engineResultIsClosedUnderResolverDemand()
    }
}

context(world: Assumptions)
private fun EngineResult?.engineResultIsClosedUnderResolverDemand(): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object -> objectIsClosedUnderResolverDemand()
        is EngineResult.List ->
            all { cell -> cell.value.engineResultIsClosedUnderResolverDemand() }
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
