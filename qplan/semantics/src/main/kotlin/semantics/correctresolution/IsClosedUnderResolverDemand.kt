package semantics.correctresolution

import model.Assumptions
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Arguments
import model.PathComponent
import model.isContextuallyGrounded
import model.groundedArguments
import model.outputValue
import model.schemaType
import model.usedVariables
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
    isClosedUnderResolverDemand(ResolverApplicationCache(this))

context(world: Assumptions)
internal fun ObjectEngineResult.isClosedUnderResolverDemand(
    resolverApplicationCache: ResolverApplicationCache,
): Boolean =
    context(resolverApplicationCache) {
        objectIsClosedUnderResolverDemand(
            path = emptyList(),
            source = null,
        )
    }

context(
    world: Assumptions,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun ObjectEngineResult.objectIsClosedUnderResolverDemand(
    path: List<PathComponent>,
    source: EngineObjectData.Sync?,
): Boolean {
    val registry = world.resolverRegistry

    return keys.all { key ->
        if (!key.isContextuallyGrounded()) return@all false
        val arguments = key.groundedArguments()
        val value = getCell(key).getValue().get()
        val fieldName = key.field.name
        val argumentsContainError = arguments.argumentsContainErrorValue()
        val sourceSuppliesField = source?.isPresent(fieldName) == true
        source.requireArgumentlessField(key)
        val fieldResolverDemandIsClosed =
            when {
                argumentsContainError -> true
                sourceSuppliesField ->
                    (arguments as? Arguments.Resolved)
                        ?.fieldValues
                        ?.isEmpty() == true
                key.field !in registry -> source == null
                else ->
                    registry
                        .resolver(key.field)
                        .let { resolver ->
                            val coordinate = path + key
                            val instantiatedSelections =
                                resolver
                                    .instantiateFragmentsAt(
                                        resolverApplicationCache.root,
                                        coordinate,
                                    ).objectFragment
                                    .constructionSelections
                            if (
                                instantiatedSelections.usedVariables().all { variable ->
                                    variable.instanceId?.let(world::isBound) == true
                                }
                            ) {
                                conformsToSelectionsAt(
                                    selections = instantiatedSelections,
                                    path = path,
                                )
                            } else {
                                val instantiatedFragment =
                                    resolver.objectFragmentAt(
                                        resolverApplicationCache.root,
                                        coordinate,
                                    )
                                conformsToSelectionsAt(
                                    instantiatedFragment,
                                    path,
                                )
                            }
                        }
            }

        fieldResolverDemandIsClosed &&
            when {
                argumentsContainError -> true
                sourceSuppliesField ->
                    value.engineResultIsClosedUnderResolverDemand(
                        path = path + key,
                        source = source.outputValue(fieldName),
                    )
                key.field in registry ->
                    reapplyResolver(key, path)?.let { application ->
                        value.engineResultIsClosedUnderResolverDemand(
                            path = path + key,
                            source = application.output,
                        )
                    } == true
                source == null ->
                    value.engineResultIsClosedUnderResolverDemand(path + key)
                else -> false
            }
    }
}

context(
    world: Assumptions,
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
