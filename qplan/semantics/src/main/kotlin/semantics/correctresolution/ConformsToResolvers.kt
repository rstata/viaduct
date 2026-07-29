package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.Schema
import model.SelectionForest
import model.Value
import model.idKeyOf
import model.toSelectionForest

/**
 * Whether every activated resolver agrees with the values attributed to it in this result tree.
 *
 * Field resolvers receive the containing object materialized according to their object fragment.
 * Node resolvers receive the containing object's `id`. Returned values are compared recursively
 * with the OER, stopping before coordinates supplied by another resolver or by the engine. The
 * complete OER tree is still traversed, so those coordinates are checked by their own resolvers.
 *
 * This predicate assumes [isClosedUnderResolverDemand] has established that every resolver input
 * cell is present. It observes cell values but never cell check components.
 */
context(world: Assumptions)
fun EngineResult.Object.conformsToResolvers(): Boolean =
    objectConformsToResolvers()

context(world: Assumptions)
private fun EngineResult.Object.objectConformsToResolvers(): Boolean {
    if (!nodeResolverConforms()) return false

    val registry = world.executorRegistry
    return keys.all { key ->
        val value = fetch(key).value
        val fieldResolverConforms =
            if (
                key.arguments.argumentsContainErrorValue() ||
                key.field !in registry
            ) {
                true
            } else {
                val resolver = registry.resolver(key.field)
                val input = materializeFragmentValue(resolver.objectFragment)
                val resolverValue = resolver.function(input, key.arguments)
                value.engineResultConformsToResolverValue(resolverValue)
            }

        fieldResolverConforms && value.engineResultConformsToResolvers()
    }
}

context(world: Assumptions)
private fun EngineResult.Object.nodeResolverConforms(): Boolean {
    if (keys.isEmpty()) return true

    val idKey = world.idKeyOf(type) ?: return true
    if (idKey !in keys) return false

    val idResult = fetch(idKey).value
    if (idResult == Value.Error || idResult !is Value.ID) return false

    val resolverValue = world.executorRegistry.resolver(type).function(idResult)
    return nodeResolverValueConforms(resolverValue)
}

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToResolvers(): Boolean =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> true

        is EngineResult.Object -> objectConformsToResolvers()
        is EngineResult.List -> all { cell -> cell.value.engineResultConformsToResolvers() }
    }

context(world: Assumptions)
private fun EngineResult.Object.materializeFragmentValue(
    fragment: Fragment,
): Value.Object =
    materializeSelectedObjectValue(fragment.subselections)

context(world: Assumptions)
private fun EngineResult.Object.materializeSelectedObjectValue(
    selections: SelectionForest,
): Value.Object {
    val selectedFields =
        selections
            .filter { selection -> type in selection.possibleTypes }
            .groupBy { selection -> selection.concreteObjectKey(type) }
            .mapValues { (key, fieldSelections) ->
                val subselections =
                    fieldSelections.flatMap { selection -> selection.subselections }
                fetch(key).value.materializeEngineResultValue(subselections)
            }

    return Value.Object.of(type, selectedFields)
}

context(world: Assumptions)
private fun EngineResult?.materializeEngineResultValue(
    selections: SelectionForest,
): Value.Output? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.Object -> materializeSelectedObjectValue(selections)
        is EngineResult.List ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values =
                    map { cell ->
                        cell.value.materializeEngineResultValue(selections)
                    },
            )
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToResolverValue(
    resolverValue: Value.Output?,
): Boolean =
    when (this) {
        null -> resolverValue == null
        Value.Error -> resolverValue == Value.Error
        is Value.Simple -> this == resolverValue

        is EngineResult.Object ->
            resolverValue != Value.Error &&
                resolverValue is Value.Object &&
                objectFieldsConformToResolverValue(
                    resolverValue = resolverValue,
                    fieldBelongsToResolver = { field -> !world.behavioral(field) },
                )

        is EngineResult.List ->
            resolverValue != Value.Error &&
            resolverValue is Value.OutputList &&
                size == resolverValue.values.size &&
                indices.all { index ->
                    get(index).value.engineResultConformsToResolverValue(
                        resolverValue.values[index],
                    )
                }
    }

context(world: Assumptions)
private fun EngineResult.Object.nodeResolverValueConforms(
    resolverValue: Value.Object,
): Boolean =
    objectFieldsConformToResolverValue(
        resolverValue = resolverValue,
        fieldBelongsToResolver = { field ->
            field.fieldName != "id" &&
                field.fieldName != "__typename" &&
                field !in world.executorRegistry
        },
    )

context(world: Assumptions)
private fun EngineResult.Object.objectFieldsConformToResolverValue(
    resolverValue: Value.Object,
    fieldBelongsToResolver: (Schema.OutputField) -> Boolean,
): Boolean {
    if (type != resolverValue.type) return false

    return keys.all { key ->
        if (!fieldBelongsToResolver(key.field)) {
            true
        } else if (!resolverValue.fieldValues.containsKey(key)) {
            false
        } else {
            fetch(key).value.engineResultConformsToResolverValue(
                resolverValue.fieldValues.getValue(key),
            )
        }
    }
}
