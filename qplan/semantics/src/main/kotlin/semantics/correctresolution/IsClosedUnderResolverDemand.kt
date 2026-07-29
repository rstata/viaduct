package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.idKeyOf

/**
 * Whether every resolver activated by this result has its required input in the same result tree.
 *
 * A present field cell activates its registered field resolver unless its arguments contain an
 * error. The containing object must then conform to that resolver's object fragment. A nonempty
 * object whose concrete type has a node resolver activates that resolver and must contain its
 * argumentless `id` bridge cell. Recursing through every present value makes these requirements
 * transitive while preserving the type guards interpreted by [conformsToFragment].
 *
 * This predicate observes cell presence and values, but never cell check components.
 */
context(world: Assumptions)
fun ObjectEngineResult.isClosedUnderResolverDemand(): Boolean =
    objectIsClosedUnderResolverDemand()

context(world: Assumptions)
private fun ObjectEngineResult.objectIsClosedUnderResolverDemand(): Boolean {
    val registry = world.executorRegistry
    if (keys.isNotEmpty()) {
        world.idKeyOf(type)?.let { idKey ->
            if (idKey !in keys) return false
        }
    }

    return keys.all { key ->
        val fieldResolverDemandIsClosed =
            key.arguments.argumentsContainErrorValue() ||
                key.field !in registry ||
                conformsToFragment(registry.resolver(key.field).objectFragment)

        fieldResolverDemandIsClosed &&
            fetch(key).value.engineResultIsClosedUnderResolverDemand()
    }
}

context(world: Assumptions)
private fun EngineResult?.engineResultIsClosedUnderResolverDemand(): Boolean =
    when (this) {
        null,
        Schema.ErrorValue,
        is Schema.SimpleValue,
        -> true

        is ObjectEngineResult -> objectIsClosedUnderResolverDemand()
        is ListEngineResult ->
            all { cell -> cell.value.engineResultIsClosedUnderResolverDemand() }
    }

internal fun Schema.ArgumentsValue.argumentsContainErrorValue(): Boolean =
    fieldValues.values.any { value -> value.inputValueContainsErrorValue() }

private fun Schema.InputValue?.inputValueContainsErrorValue(): Boolean =
    when {
        this == Schema.ErrorValue -> true
        this is Schema.InputListValue ->
            values.any { element -> element.inputValueContainsErrorValue() }

        this is Schema.InputObjectValue ->
            fieldValues.values.any { value -> value.inputValueContainsErrorValue() }

        else -> false
    }
