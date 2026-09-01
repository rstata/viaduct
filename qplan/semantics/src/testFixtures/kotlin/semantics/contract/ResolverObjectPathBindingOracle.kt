package semantics.contract

import model.Assumptions
import model.EngineInputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import viaduct.graphql.schema.ViaductSchema
import model.Selection
import model.VariableBinding
import model.objectKey
import model.outputType
import model.registry.FieldResolver
import model.registry.InstantiatedObjectPathDefinition
import model.selectionForestOf
import model.toEngineInputListData
import model.toEngineSimpleData
import semantics.findStoredKey
import semantics.arbitrary.forEachRegisteredResolverOccurrence
import viaduct.utils.collections.BitVector
import kotlin.test.assertEquals

/**
 * Independently validates object-path bindings across every request-local Query root.
 *
 * An applied occurrence must have exactly all of its declared bindings and a passive occurrence
 * must have none.
 */
context(world: Assumptions)
fun ObjectEngineResult.validateObjectPathBindings(
    appliedResolverOccurrences: Set<ResolverOccurrenceId>,
) {
    requestQueryRoots().forEach { root ->
        root.forEachRegisteredResolverOccurrence(world.resolverRegistry) { cell ->
            val resolver = world.resolverRegistry.resolver(cell.field)
            val definitions =
                resolver.objectPathDefinitions(
                    root = root,
                    path = cell.occurrencePath,
                )
            if (definitions.isEmpty()) {
                return@forEachRegisteredResolverOccurrence
            }

            val occurrenceId = ResolverOccurrenceId.at(root, cell.occurrencePath)
            val requiredBindingIds =
                definitions.mapTo(linkedSetOf()) { definition ->
                    requireNotNull(definition.variable.instanceId)
                }
            val actualBindingIds = requiredBindingIds.filterTo(linkedSetOf(), world::isBound)
            when (appliedResolverOccurrences.contains(occurrenceId)) {
                true ->
                    assertEquals(
                        requiredBindingIds,
                        actualBindingIds,
                        "Applied resolver occurrence $occurrenceId has incomplete " +
                            "FromObjectField bindings",
                    )
                false -> {
                    assertEquals(
                        emptySet(),
                        actualBindingIds,
                        "Passive resolver occurrence $occurrenceId unexpectedly has " +
                            "FromObjectField bindings",
                    )
                    return@forEachRegisteredResolverOccurrence
                }
            }

            definitions.forEach { definition ->
                val expected =
                    cell.containingObject.readCompletedProvider(
                        path = definition.path,
                        reader = cell.occurrencePath,
                    )
                assertEquals(
                    expected,
                    world.getBinding(requireNotNull(definition.variable.instanceId)),
                )
            }
        }
    }
}

internal fun FieldResolver.objectPathDefinitions(
    root: ObjectEngineResult,
    path: List<PathComponent>,
): List<InstantiatedObjectPathDefinition> =
    instantiateObjectFragmentAt(root, path).pathVariableDefinitions

context(world: Assumptions)
private fun ObjectEngineResult.requestQueryRoots(): List<ObjectEngineResult> =
    buildList {
        add(this@requestQueryRoots)
        addAll(world.queryValues.values)
    }

context(world: Assumptions)
private fun ObjectEngineResult.readCompletedProvider(
    path: List<ObjectEngineResult.Key>,
    reader: List<PathComponent>,
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
            current.findStoredKey(specialized)
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

private fun EngineResult.toVariableBinding(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
): VariableBinding =
    when (this) {
        is ErrorEngineResult -> VariableBinding.Error
        is ListEngineResult -> toInputListBinding()
        is ObjectEngineResult ->
            error("An object-path provider cannot terminate at an object")
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
