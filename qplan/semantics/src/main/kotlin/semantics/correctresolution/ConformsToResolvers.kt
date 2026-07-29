package semantics.correctresolution

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.SelectionForest
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
fun ObjectEngineResult.conformsToResolvers(): Boolean =
    objectConformsToResolvers()

context(world: Assumptions)
private fun ObjectEngineResult.objectConformsToResolvers(): Boolean {
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
private fun ObjectEngineResult.nodeResolverConforms(): Boolean {
    if (keys.isEmpty()) return true

    val idKey = world.idKeyOf(type) ?: return true
    if (idKey !in keys) return false

    val idResult = fetch(idKey).value
    if (idResult == Schema.ErrorValue || idResult !is Schema.IDValue) return false

    val resolverValue = world.executorRegistry.resolver(type).function(idResult)
    return nodeResolverValueConforms(resolverValue)
}

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToResolvers(): Boolean =
    when (this) {
        null,
        Schema.ErrorValue,
        is Schema.SimpleValue,
        -> true

        is ObjectEngineResult -> objectConformsToResolvers()
        is ListEngineResult -> all { cell -> cell.value.engineResultConformsToResolvers() }
    }

context(world: Assumptions)
private fun ObjectEngineResult.materializeFragmentValue(
    fragment: Fragment,
): Schema.ObjectValue =
    materializeSelectedObjectValue(fragment.subselections)

context(world: Assumptions)
private fun ObjectEngineResult.materializeSelectedObjectValue(
    selections: SelectionForest,
): Schema.ObjectValue {
    val selectedFields =
        selections
            .filter { selection -> type in selection.possibleTypes }
            .groupBy { selection -> selection.concreteObjectKey(type) }
            .mapValues { (key, fieldSelections) ->
                val subselections =
                    fieldSelections.flatMap { selection -> selection.subselections }
                fetch(key).value.materializeEngineResultValue(subselections)
            }

    return Schema.ObjectValue.of(type, selectedFields)
}

context(world: Assumptions)
private fun EngineResult?.materializeEngineResultValue(
    selections: SelectionForest,
): Schema.OutputValue? =
    when (this) {
        null -> null
        Schema.ErrorValue -> Schema.ErrorValue
        is Schema.SimpleValue -> this
        is ObjectEngineResult -> materializeSelectedObjectValue(selections)
        is ListEngineResult ->
            Schema.OutputListValue.of(
                typeExpr = typeExpr,
                values =
                    map { cell ->
                        cell.value.materializeEngineResultValue(selections)
                    },
            )
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToResolverValue(
    resolverValue: Schema.OutputValue?,
): Boolean =
    when (this) {
        null -> resolverValue == null
        Schema.ErrorValue -> resolverValue == Schema.ErrorValue
        is Schema.SimpleValue -> this == resolverValue

        is ObjectEngineResult ->
            resolverValue != Schema.ErrorValue &&
                resolverValue is Schema.ObjectValue &&
                objectFieldsConformToResolverValue(
                    resolverValue = resolverValue,
                    fieldBelongsToResolver = { field -> !world.behavioral(field) },
                )

        is ListEngineResult ->
            resolverValue != Schema.ErrorValue &&
            resolverValue is Schema.OutputListValue &&
                size == resolverValue.values.size &&
                indices.all { index ->
                    get(index).value.engineResultConformsToResolverValue(
                        resolverValue.values[index],
                    )
                }
    }

context(world: Assumptions)
private fun ObjectEngineResult.nodeResolverValueConforms(
    resolverValue: Schema.ObjectValue,
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
private fun ObjectEngineResult.objectFieldsConformToResolverValue(
    resolverValue: Schema.ObjectValue,
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
