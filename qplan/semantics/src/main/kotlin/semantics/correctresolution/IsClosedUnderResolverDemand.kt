package semantics.correctresolution

import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Arguments
import model.PathComponent
import semantics.shared.groundedArguments
import semantics.shared.isContextuallyGrounded
import semantics.shared.objectFragmentAt
import model.outputValue
import model.schemaType
import model.usedVariables
import viaduct.engine.api.EngineObjectData
import semantics.shared.OperationContext
import viaduct.graphql.schema.ViaductSchema

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
context(operation: OperationContext)
fun ObjectEngineResult.isClosedUnderResolverDemand(): Boolean =
    isClosedUnderResolverDemand(ResolverApplicationCache(this))

context(operation: OperationContext)
internal fun ObjectEngineResult.isClosedUnderResolverDemand(
    resolverApplicationCache: ResolverApplicationCache,
): Boolean =
    context(resolverApplicationCache) {
        objectIsClosedUnderResolverDemand(
            path = emptyList(),
            source = null,
            structuralParent = null,
            producerField = null,
        )
    }

context(
    operation: OperationContext,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun ObjectEngineResult.objectIsClosedUnderResolverDemand(
    path: List<PathComponent>,
    source: EngineObjectData.Sync?,
    structuralParent: ObjectEngineResult?,
    producerField: ViaductSchema.ObjectField?,
): Boolean {
    val registry = operation.resolverRegistry

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
                key is ObjectEngineResult.ParentKey ->
                    value === structuralParent &&
                        operation.world.parentFieldRelations[key.field] == producerField
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
                                    variable.instanceId?.let(
                                        operation.variableBindingsState::isBound,
                                    ) == true
                                }
                            ) {
                                context(operation.world) {
                                    conformsToSelectionsAt(
                                        selections = instantiatedSelections,
                                        path = path,
                                    )
                                }
                            } else {
                                val instantiatedFragment =
                                    resolver.objectFragmentAt(
                                        resolverApplicationCache.root,
                                        coordinate,
                                    )
                                context(operation.world) {
                                    conformsToSelectionsAt(
                                        instantiatedFragment,
                                        path,
                                    )
                                }
                            }
                        }
            }

        fieldResolverDemandIsClosed &&
            when {
                key is ObjectEngineResult.ParentKey -> true
                argumentsContainError -> true
                sourceSuppliesField ->
                    value.engineResultIsClosedUnderResolverDemand(
                        path = path + key,
                        source = source.outputValue(fieldName),
                        structuralParent = this,
                        producerField = key.field,
                    )
                key.field in registry ->
                    reapplyResolver(key, path)?.let { application ->
                        value.engineResultIsClosedUnderResolverDemand(
                            path = path + key,
                            source = application.output,
                            structuralParent = this,
                            producerField = key.field,
                        )
                    } == true
                source == null ->
                    value.engineResultIsClosedUnderResolverDemand(
                        path = path + key,
                        structuralParent = this,
                        producerField = key.field,
                    )
                else -> false
            }
    }
}

context(
    operation: OperationContext,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun EngineResult?.engineResultIsClosedUnderResolverDemand(
    path: List<PathComponent>,
    source: EngineOutputData?,
    structuralParent: ObjectEngineResult,
    producerField: ViaductSchema.ObjectField,
): Boolean =
    when (this) {
        null,
        is ErrorEngineResult,
        -> true

        is ObjectEngineResult ->
            source is EngineObjectData.Sync &&
                type == source.schemaType &&
                objectIsClosedUnderResolverDemand(
                    path = path,
                    source = source,
                    structuralParent = structuralParent,
                    producerField = producerField,
                )
        is ListEngineResult ->
            source is List<*> &&
                size == source.size &&
                indices.all { index ->
                    get(index).getValue().get().engineResultIsClosedUnderResolverDemand(
                        path = path + ListEngineResult.Index.of(index),
                        source = source[index],
                        structuralParent = structuralParent,
                        producerField = producerField,
                    )
                }
        else -> true
    }

context(
    operation: OperationContext,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun EngineResult?.engineResultIsClosedUnderResolverDemand(
    path: List<PathComponent>,
    structuralParent: ObjectEngineResult,
    producerField: ViaductSchema.ObjectField,
): Boolean =
    when (this) {
        null,
        is ErrorEngineResult,
        -> true

        is ObjectEngineResult ->
            objectIsClosedUnderResolverDemand(
                path = path,
                source = null,
                structuralParent = structuralParent,
                producerField = producerField,
            )
        is ListEngineResult ->
            indices.all { index ->
                get(index).getValue().get().engineResultIsClosedUnderResolverDemand(
                    path = path + ListEngineResult.Index.of(index),
                    structuralParent = structuralParent,
                    producerField = producerField,
                )
            }
        else -> true
    }

internal fun Arguments.Ground.argumentsContainErrorValue(): Boolean =
    this == Arguments.Error
