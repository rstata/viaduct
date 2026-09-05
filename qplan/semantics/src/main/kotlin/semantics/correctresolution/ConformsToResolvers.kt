package semantics.correctresolution

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.outputType
import model.outputValue
import model.PathComponent
import model.VariableBinding
import semantics.shared.groundedArguments
import semantics.shared.isContextuallyGrounded
import model.schemaType
import viaduct.engine.api.EngineObjectData
import model.toEngineOutputData
import model.usedVariables
import model.registry.FieldResolver
import model.registry.ResolverFragments
import model.registry.VariableDefinition
import semantics.shared.OperationContext

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
context(operation: OperationContext)
fun ObjectEngineResult.conformsToResolvers(): Boolean =
    conformsToResolvers(ResolverApplicationCache(this))

context(operation: OperationContext)
internal fun ObjectEngineResult.conformsToResolvers(
    resolverApplicationCache: ResolverApplicationCache,
): Boolean =
    context(resolverApplicationCache) {
        objectConformsToResolvers(
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
private fun ObjectEngineResult.objectConformsToResolvers(
    path: List<PathComponent>,
    source: EngineObjectData.Sync?,
    structuralParent: ObjectEngineResult?,
    producerField: ViaductSchema.ObjectField?,
): Boolean =
    keys.all { key ->
        if (!key.isContextuallyGrounded()) return@all false
        val value = getCell(key).getValue().get()
        val arguments = key.groundedArguments()
        val fieldName = key.field.name
        source.requireArgumentlessField(key)
        when {
            key is ObjectEngineResult.ParentKey ->
                value === structuralParent &&
                    operation.world.parentFieldRelations[key.field] == producerField

            arguments !is Arguments.Resolved ->
                value is ErrorEngineResult

            source != null && source.isPresent(fieldName) ->
                arguments.fieldValues.isEmpty() &&
                    value.engineResultConformsToResolverValue(
                        resolverValue = source.outputValue(fieldName),
                        expectedType = key.field.outputType,
                        path = path + key,
                        structuralParent = this,
                        producerField = key.field,
                    )

            key.field in operation.resolverRegistry ->
                checkNotNull(reapplyResolver(key, path))
                    .let { application ->
                        value.engineResultConformsToResolverValue(
                            resolverValue = application.output,
                            expectedType = key.field.outputType,
                            path = path + key,
                            structuralParent = this,
                            producerField = key.field,
                        )
                    }

            source == null ->
                value.engineResultConformsToResolvers(
                    path = path + key,
                    structuralParent = this,
                    producerField = key.field,
                )

            else -> false
        }
    }

context(operation: OperationContext)
internal fun FieldResolver.fragmentsSatisfiedBy(
    root: ObjectEngineResult,
    result: ObjectEngineResult,
    path: List<PathComponent>,
): ResolverFragments? {
    val fragments = instantiateFragmentsAt(root, path)
    val objectFragment = fragments.objectFragment
    val arguments =
        (path.lastOrNull() as? ObjectEngineResult.ObjectKey)
            ?.groundedArguments() as? Arguments.Resolved
            ?: return null
    return fragments.takeIf {
        val constructionSelections = objectFragment.constructionSelections
        fromArgumentBindingsAgree(
            fragments = fragments,
            arguments = arguments,
        ) &&
            constructionSelections.usedVariables().all { variable ->
                operation.variableBindingsState.isBound(requireNotNull(variable.instanceId))
            } &&
            context(operation.world) {
                result.conformsToSelectionsAt(
                    selections = constructionSelections,
                    path = path.dropLast(1),
                )
            }
    }
}

context(operation: OperationContext)
private fun FieldResolver.fromArgumentBindingsAgree(
    fragments: ResolverFragments,
    arguments: Arguments.Resolved,
): Boolean {
    val usedVariables =
        fragments.objectFragment.constructionSelections.usedVariables() +
            fragments.queryFragment.constructionSelections.usedVariables()
    val resolverOccurrenceId = fragments.objectFragment.resolverOccurrenceId
    return instantiatedVariableDefinitions(resolverOccurrenceId)
        .filter { variableDefinition -> variableDefinition.variable in usedVariables }
        .all { variableDefinition ->
            val definition = variableDefinition.definition
            if (definition !is VariableDefinition.FromArgument) return@all true
            val instanceId = requireNotNull(variableDefinition.variable.instanceId)
            operation.variableBindingsState.isBound(instanceId) &&
                operation.variableBindingsState.getBinding(instanceId) ==
                VariableBinding.of(definition.read(arguments))
        }
}

context(
    operation: OperationContext,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun EngineResult?.engineResultConformsToResolvers(
    path: List<PathComponent>,
    structuralParent: ObjectEngineResult,
    producerField: ViaductSchema.ObjectField,
): Boolean =
    when (this) {
        null,
        is ErrorEngineResult,
        -> true

        is ObjectEngineResult ->
            objectConformsToResolvers(
                path = path,
                source = null,
                structuralParent = structuralParent,
                producerField = producerField,
            )
        is ListEngineResult ->
            indices.all { index ->
                get(index).getValue().get().engineResultConformsToResolvers(
                    path = path + ListEngineResult.Index.of(index),
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
private fun EngineResult?.engineResultConformsToResolverValue(
    resolverValue: EngineOutputData?,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    structuralParent: ObjectEngineResult,
    producerField: ViaductSchema.ObjectField,
): Boolean =
    when (this) {
        null -> resolverValue == null
        is ErrorEngineResult -> resolverValue is EngineErrorData

        is ObjectEngineResult ->
            resolverValue is EngineObjectData.Sync &&
                objectFieldsConformToResolverValue(
                    resolverValue = resolverValue,
                    path = path,
                    structuralParent = structuralParent,
                    producerField = producerField,
                )

        is ListEngineResult ->
            resolverValue is List<*> &&
                size == resolverValue.size &&
                indices.all { index ->
                    get(index).getValue().get().engineResultConformsToResolverValue(
                        resolverValue[index],
                        typeExpr,
                        path + ListEngineResult.Index.of(index),
                        structuralParent,
                        producerField,
                    )
                }

        else ->
            toEngineOutputData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef) ==
                resolverValue
    }

context(
    operation: OperationContext,
    resolverApplicationCache: ResolverApplicationCache,
)
private fun ObjectEngineResult.objectFieldsConformToResolverValue(
    resolverValue: EngineObjectData.Sync,
    path: List<PathComponent>,
    structuralParent: ObjectEngineResult,
    producerField: ViaductSchema.ObjectField,
): Boolean {
    if (type != resolverValue.schemaType) return false

    return objectConformsToResolvers(
        path = path,
        source = resolverValue,
        structuralParent = structuralParent,
        producerField = producerField,
    )
}
