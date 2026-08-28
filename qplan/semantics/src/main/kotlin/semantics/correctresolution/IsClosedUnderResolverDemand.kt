package semantics.correctresolution

import model.Assumptions
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Arguments
import model.PathComponent
import model.Stamp
import model.applicableGroundSelections
import model.outputValue
import model.schemaType
import model.usedVariables
import semantics.ResolverSupport
import viaduct.engine.api.EngineObjectData

/**
 * Whether every standard registered resolver activated by this result has its required input.
 *
 * A source object exceptionally owns every argumentless field it supplies, including null and
 * error values. A source-absent registered field activates its standard resolver unless its
 * arguments contain an error. Resolver outputs are deterministically reapplied to classify
 * descendant occurrences.
 *
 * This predicate observes cell-value presence and content, but never access-acceptance results.
 */
context(world: Assumptions)
fun ObjectEngineResult.isClosedUnderResolverDemand(): Boolean =
    isClosedUnderResolverDemand(ResolverApplicationCache())

context(world: Assumptions)
internal fun ObjectEngineResult.isClosedUnderResolverDemand(
    resolverApplicationCache: ResolverApplicationCache,
): Boolean =
    context(
        ResolverSupport.noCycleChecking { selections -> selections },
        resolverApplicationCache,
    ) {
        objectIsClosedUnderResolverDemand(
            path = emptyList(),
            source = null,
        )
    }

context(
    world: Assumptions,
    resolverSupport: ResolverSupport,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun ObjectEngineResult.objectIsClosedUnderResolverDemand(
    path: List<PathComponent>,
    source: EngineObjectData.Sync?,
): Boolean {
    val registry = world.resolverRegistry

    return keys.all { groundKey ->
        val value = getCell(groundKey).getValue().get()
        val fieldName = groundKey.field.name
        val argumentsContainError = groundKey.arguments.argumentsContainErrorValue()
        val sourceSuppliesField = source?.isPresent(fieldName) == true
        source.requireArgumentlessField(groundKey)
        val fieldResolverDemandIsClosed =
            when {
                argumentsContainError -> true
                sourceSuppliesField ->
                    (groundKey.arguments as? Arguments.Resolved)
                        ?.fieldValues
                        ?.isEmpty() == true
                groundKey.field !in registry -> source == null
                else ->
                    registry
                        .resolver(groundKey.field)
                        .let { resolver ->
                            val coordinate = path + groundKey
                            val selectionStamp = groundKey.stamp as? Stamp.Occurrence
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
                                            groundKey.field.containingDef,
                                        ),
                                    path = path,
                                )
                            } else {
                                val variableStamped = resolver.objectFragmentAt(coordinate)
                                conformsToSelectionsAt(variableStamped, path)
                            }
                        }
            }

        fieldResolverDemandIsClosed &&
            when {
                argumentsContainError -> true
                sourceSuppliesField ->
                    value.engineResultIsClosedUnderResolverDemand(
                        path = path + groundKey,
                        source = source.outputValue(fieldName),
                    )
                groundKey.field in registry ->
                    reapplyResolver(groundKey, path)?.let { application ->
                        value.engineResultIsClosedUnderResolverDemand(
                            path = path + groundKey,
                            source = application.output,
                        )
                    } == true
                source == null -> value.engineResultIsClosedUnderResolverDemand(path + groundKey)
                else -> false
            }
    }
}

context(
    world: Assumptions,
    resolverSupport: ResolverSupport,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun EngineResult?.engineResultIsClosedUnderResolverDemand(
    path: List<PathComponent>,
    source: EngineOutputData?,
): Boolean =
    when (this) {
        null,
        is ErrorEngineResult,
        -> true

        is ObjectEngineResult ->
            source is EngineObjectData.Sync &&
                type == source.schemaType &&
                objectIsClosedUnderResolverDemand(path, source)
        is ListEngineResult ->
            source is List<*> &&
                size == source.size &&
                indices.all { index ->
                    get(index).getValue().get().engineResultIsClosedUnderResolverDemand(
                        path = path + ListEngineResult.Index.of(index),
                        source = source[index],
                    )
                }
        else -> true
    }

context(
    world: Assumptions,
    resolverSupport: ResolverSupport,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun EngineResult?.engineResultIsClosedUnderResolverDemand(
    path: List<PathComponent>,
): Boolean =
    when (this) {
        null,
        is ErrorEngineResult,
        -> true

        is ObjectEngineResult ->
            objectIsClosedUnderResolverDemand(
                path = path,
                source = null,
            )
        is ListEngineResult ->
            indices.all { index ->
                get(index).getValue().get().engineResultIsClosedUnderResolverDemand(
                    path + ListEngineResult.Index.of(index),
                )
            }
        else -> true
    }

internal fun Arguments.Ground.argumentsContainErrorValue(): Boolean =
    this == Arguments.Error
