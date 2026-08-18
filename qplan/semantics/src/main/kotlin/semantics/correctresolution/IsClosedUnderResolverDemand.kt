package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.SimpleEngineResult
import model.Value
import model.applicableGroundSelections
import model.usedVariables

/**
 * Whether every resolver activated by this result has its required input in the same result tree.
 *
 * A present field value activates its registered field resolver unless its arguments contain an
 * error. The containing object must then conform to that resolver's object fragment. Recursing
 * through every present value makes these requirements transitive while preserving the type guards
 * interpreted by [conformsToSelections].
 *
 * This predicate observes cell-value presence and content, but never access-acceptance results.
 */
context(world: Assumptions)
fun ObjectEngineResult.isClosedUnderResolverDemand(): Boolean =
    objectIsClosedUnderResolverDemand(emptyList())

context(world: Assumptions)
private fun ObjectEngineResult.objectIsClosedUnderResolverDemand(
    path: List<PathComponent>,
): Boolean {
    val registry = world.resolverRegistry

    return keys.all { groundKey ->
        val fieldResolverDemandIsClosed =
            groundKey.arguments.argumentsContainErrorValue() ||
                groundKey.field !in registry ||
                registry
                    .resolver(groundKey.field)
                    .let { resolver ->
                        val coordinate = path + groundKey
                        val selectionStamp = groundKey.selectionStamp
                        val selectionStamped =
                            if (selectionStamp != null) {
                                resolver.stampFrom(selectionStamp)
                            } else {
                                resolver.stamp(coordinate)
                            }
                        if (
                            selectionStamped.usedVariables().all { variable ->
                                variable.isStamped && world.isBound(variable)
                            }
                        ) {
                            conformsToSelectionsAt(
                                selections =
                                    selectionStamped.applicableGroundSelections(
                                        groundKey.field.containingType,
                                    ),
                                path = path,
                            )
                        } else {
                            val variableStamped = resolver.objectFragmentAt(coordinate)
                            conformsToSelectionsAt(variableStamped, path)
                        }
                    }

        fieldResolverDemandIsClosed &&
            getCell(groundKey)
                .getValue()
                .get()
                .engineResultIsClosedUnderResolverDemand(path + groundKey)
    }
}

context(world: Assumptions)
private fun EngineResult?.engineResultIsClosedUnderResolverDemand(
    path: List<PathComponent>,
): Boolean =
    when (this) {
        null,
        ErrorEngineResult,
        is SimpleEngineResult,
        -> true

        is ObjectEngineResult -> objectIsClosedUnderResolverDemand(path)
        is ListEngineResult ->
            indices.all { index ->
                get(index).getValue().get().engineResultIsClosedUnderResolverDemand(
                    path + ListEngineResult.Index.of(index),
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
