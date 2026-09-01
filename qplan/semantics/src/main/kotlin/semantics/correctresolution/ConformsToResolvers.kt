package semantics.correctresolution

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.outputType
import model.outputValue
import model.PathComponent
import model.isContextuallyGrounded
import model.groundedArguments
import model.schemaType
import viaduct.engine.api.EngineObjectData
import model.toEngineOutputData
import model.usedVariables
import model.registry.FieldResolver
import model.registry.ResolverObjectFragment
import semantics.ResolverSupport

/**
 * Whether every value agrees with the resolver output that owns its exact occurrence.
 *
 * A source object exceptionally owns every argumentless field it supplies, including null and
 * error values. An absent registered field is owned by its standard registered resolver. Field
 * resolvers receive the containing object materialized according to their object fragment.
 *
 * This predicate assumes [isClosedUnderResolverDemand] has established that every resolver input
 * value is present. It observes cell values but never access-acceptance results.
 */
context(world: Assumptions)
fun ObjectEngineResult.conformsToResolvers(): Boolean =
    conformsToResolvers(ResolverApplicationCache())

context(world: Assumptions)
internal fun ObjectEngineResult.conformsToResolvers(
    resolverApplicationCache: ResolverApplicationCache,
): Boolean =
    context(
        ResolverSupport.noCycleChecking { selections -> selections },
        resolverApplicationCache,
    ) {
        objectConformsToResolvers(
            path = emptyList(),
            source = null,
        )
    }

context(
    world: Assumptions,
    resolverSupport: ResolverSupport,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun ObjectEngineResult.objectConformsToResolvers(
    path: List<PathComponent>,
    source: EngineObjectData.Sync?,
): Boolean =
    keys.all { key ->
        if (!key.isContextuallyGrounded()) return@all false
        val value = getCell(key).getValue().get()
        val arguments = key.groundedArguments()
        val fieldName = key.field.name
        source.requireArgumentlessField(key)
        when {
            arguments !is Arguments.Resolved ->
                value is ErrorEngineResult

            source?.isPresent(fieldName) == true ->
                arguments.fieldValues.isEmpty() &&
                    value.engineResultConformsToResolverValue(
                        resolverValue = source.outputValue(fieldName),
                        expectedType = key.field.outputType,
                        path = path + key,
                    )

            key.field in world.resolverRegistry ->
                reapplyResolver(key, path)
                    ?.let { application ->
                        value.engineResultConformsToResolverValue(
                            resolverValue = application.output,
                            expectedType = key.field.outputType,
                            path = path + key,
                        )
                    } == true

            source == null ->
                value.engineResultConformsToResolvers(path + key)

            else -> false
        }
    }

context(world: Assumptions)
internal fun FieldResolver.objectFragmentSatisfiedBy(
    result: ObjectEngineResult,
    path: List<PathComponent>,
): ResolverObjectFragment? {
    val objectFragment = instantiateObjectFragmentAt(path)
    return objectFragment.takeIf {
        val constructionSelections = objectFragment.constructionSelections
        constructionSelections.usedVariables().all { variable ->
            variable.instanceId?.let(world::isBound) == true
        } &&
            result.conformsToSelectionsAt(
                selections = constructionSelections,
                path = path.dropLast(1),
            )
    }
}

context(
    world: Assumptions,
    resolverSupport: ResolverSupport,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun EngineResult?.engineResultConformsToResolvers(
    path: List<PathComponent>,
): Boolean =
    when (this) {
        null,
        is ErrorEngineResult,
        -> true

        is ObjectEngineResult ->
            objectConformsToResolvers(
                path = path,
                source = null,
            )
        is ListEngineResult ->
            indices.all { index ->
                get(index).getValue().get().engineResultConformsToResolvers(
                    path + ListEngineResult.Index.of(index),
                )
            }
        else -> true
    }

context(
    world: Assumptions,
    resolverSupport: ResolverSupport,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun EngineResult?.engineResultConformsToResolverValue(
    resolverValue: EngineOutputData?,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
): Boolean =
    when (this) {
        null -> resolverValue == null
        is ErrorEngineResult -> resolverValue is EngineErrorData

        is ObjectEngineResult ->
            resolverValue is EngineObjectData.Sync &&
                objectFieldsConformToResolverValue(
                    resolverValue = resolverValue,
                    path = path,
                )

        is ListEngineResult ->
            resolverValue is List<*> &&
                size == resolverValue.size &&
                indices.all { index ->
                    get(index).getValue().get().engineResultConformsToResolverValue(
                        resolverValue[index],
                        typeExpr,
                        path + ListEngineResult.Index.of(index),
                    )
                }

        else ->
            toEngineOutputData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef) ==
                resolverValue
    }

context(
    world: Assumptions,
    resolverSupport: ResolverSupport,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun ObjectEngineResult.objectFieldsConformToResolverValue(
    resolverValue: EngineObjectData.Sync,
    path: List<PathComponent>,
): Boolean {
    if (type != resolverValue.schemaType) return false

    return objectConformsToResolvers(path, resolverValue)
}
