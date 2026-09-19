package semantics.contract

import model.EngineInputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.Selection
import model.VariableBinding
import model.objectKey
import model.outputType
import model.registry.FieldResolver
import model.registry.InstantiatedFieldPathDefinition
import model.registry.ProviderFragment
import model.selectionForestOf
import model.toEngineInputListData
import model.toEngineSimpleData
import semantics.shared.findStoredKey
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector
import kotlin.test.assertEquals
import semantics.shared.SharedOperationContext
import semantics.shared.ResolverObservations

/**
 * Independently validates from-field bindings across every request-local Query root.
 *
 * An applied occurrence must have exactly all of its declared bindings and a passive occurrence
 * must have none.
 */
fun ObjectEngineResult.validateFromFieldBindings(
    operation: SharedOperationContext<*>,
    appliedResolverOccurrences: Set<ResolverOccurrenceId>,
) {
    fun validateOccurrence(
        root: ObjectEngineResult,
        field: ViaductSchema.ObjectField,
        path: List<PathComponent>,
        containingObject: ObjectEngineResult?,
    ) {
        val resolver = operation.world.resolverRegistry.resolver(field)
        val definitions = resolver.fieldPathDefinitions(root = root, path = path)
        if (definitions.isEmpty()) return

        val occurrenceId = ResolverOccurrenceId.at(root, path)
        val requiredBindingIds =
            definitions.mapTo(linkedSetOf()) { definition ->
                requireNotNull(definition.variable.instanceId)
            }
        val actualBindingIds =
            requiredBindingIds.filterTo(
                linkedSetOf(),
                operation.variableBindings::isBound,
            )
        when (appliedResolverOccurrences.contains(occurrenceId)) {
            true ->
                assertEquals(
                    requiredBindingIds,
                    actualBindingIds,
                    "Applied resolver occurrence $occurrenceId has incomplete from-field bindings",
                )
            false -> {
                assertEquals(
                    emptySet(),
                    actualBindingIds,
                    "Passive resolver occurrence $occurrenceId unexpectedly has from-field bindings",
                )
                return
            }
        }

        definitions.forEach { definition ->
            val providerRoot =
                when (definition.providerFragment) {
                    ProviderFragment.OBJECT -> containingObject
                        ?: error("Root-field-reference target unexpectedly has an object-field provider")
                    ProviderFragment.QUERY ->
                        operation.resolverObservations()
                            .queryFragmentResults(occurrenceId)
                            .single()
                }
            val expected = providerRoot.readCompletedProvider(operation = operation, path = definition.path)
            assertEquals(
                expected,
                operation.variableBindings.getBinding(
                    requireNotNull(definition.variable.instanceId),
                ),
            )
        }
    }

    requestQueryRoots(operation).forEach { root ->
        root.forEachRegisteredResolverOccurrence(operation, operation.world.resolverRegistry) { cell ->
            validateOccurrence(root, cell.field, cell.occurrencePath, cell.containingObject)
        }
    }
    operation.resolverObservations().rootFieldReferenceInvocations().forEach { observation ->
        validateOccurrence(
            observation.invocationRoot,
            observation.invocationKey.field,
            observation.invocationPath,
            null,
        )
    }
}

internal fun FieldResolver.fieldPathDefinitions(
    root: ObjectEngineResult,
    path: List<PathComponent>,
): List<InstantiatedFieldPathDefinition> =
    instantiatedFieldPathVariableDefinitions(ResolverOccurrenceId.at(root, path))

private fun ObjectEngineResult.requestQueryRoots(operation: SharedOperationContext<*>): List<ObjectEngineResult> =
    buildList {
        add(this@requestQueryRoots)
        addAll(
            operation.resolverObservations()
                .allQueryFragmentResults()
                .values
                .flatten(),
        )
    }

private fun ObjectEngineResult.readCompletedProvider(
    operation: SharedOperationContext<*>,
    path: List<ObjectEngineResult.Key>,
): VariableBinding {
    var current = this
    path.forEachIndexed { index, openKey ->
        val specialized =
            Selection.of(
                key = openKey,
                possibleTypes = setOf(current.type),
                subselections = selectionForestOf(),
            ).objectKey(current.type)
        val key =
            current.findStoredKey(operation, specialized)
                ?: error("Completed provider key is absent from result: $specialized")
        val value = current.getCell(key).get()
        if (value == null) return VariableBinding.of(null)
        if (value is ErrorEngineResult) return VariableBinding.Error
        if (index == path.lastIndex) {
            return value.toVariableBinding(key.field.outputType)
        }
        current =
            value as? ObjectEngineResult
                ?: error("Completed provider path crossed a non-object at $key")
    }
    error("Provider path must be nonempty")
}

private fun SharedOperationContext<*>.resolverObservations(): ResolverObservations =
    resolverObserver as? ResolverObservations
        ?: error("Resolver observations were not recorded for this operation")

private fun EngineResult.toVariableBinding(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
): VariableBinding =
    when (this) {
        is ErrorEngineResult -> VariableBinding.Error
        is ListEngineResult -> toInputListBinding()
        is ObjectEngineResult ->
            error("A from-field provider cannot terminate at an object")
        else ->
            VariableBinding.of(
                toEngineSimpleData(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef),
            )
    }

private fun ListEngineResult.toInputListBinding(): VariableBinding {
    val baseType = typeExpr.baseTypeDef
    require(baseType is ViaductSchema.InputTypeDef)
    val values = mutableListOf<EngineInputData?>()
    indices.forEach { index ->
        val result = get(index).get()
        val binding =
            if (result == null) {
                VariableBinding.of(null)
            } else {
                result.toVariableBinding(typeExpr)
            }
        when (binding) {
            VariableBinding.Error -> return VariableBinding.Error
            is VariableBinding.Input -> values += binding.value
        }
    }
    return VariableBinding.of(
        toEngineInputListData(
            expectedType = typeExpr.withNonNullListWrapper(baseType),
            value = values,
        ),
    )
}

private fun ViaductSchema.TypeExpr<*>.withNonNullListWrapper(
    baseType: ViaductSchema.InputTypeDef,
): ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef> {
    val wrappers = BitVector(listDepth + 1)
    for (depth in 0 until listDepth) {
        if (nullableAtDepth(depth)) wrappers.set(depth + 1)
    }
    return ViaductSchema.TypeExpr(baseType, baseTypeNullable, wrappers)
}
