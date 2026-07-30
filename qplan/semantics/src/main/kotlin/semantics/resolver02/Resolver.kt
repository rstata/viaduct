package semantics.resolver02

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.idKeyOf
import model.registry.demandsFromSibling
import model.selectionForestOf
import model.union
import semantics.correctresolution.argumentsContainErrorValue
import semantics.correctresolution.concreteObjectKey
import semantics.materialize

/**
 * Returns the result for [selections] and all transitive resolver demand on this concrete object.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    val closedSelections = closeResolverDemand(selections)
    val orderedKeys = dependencyOrder(closedSelections.keys)
    val emptyResult = EngineResult.Object.of(type, emptyMap())

    return orderedKeys.fold(emptyResult) { result, key ->
        result.union(resolveKey(key, closedSelections.getValue(key), result))
    }
}

/**
 * Returns the applicable selections by concrete key, including all transitive resolver demand.
 */
context(world: Assumptions)
private fun Value.Object.closeResolverDemand(
    selections: SelectionForest,
    expanded: Set<Value.Key> = emptySet(),
): Map<Value.Key, SelectionForest> {
    val applicableSelections =
        selections.filter { selection -> type in selection.possibleTypes }
    val groupedSelections =
        applicableSelections.groupBy { selection -> selection.concreteObjectKey(type) }
    val unexpandedResolverKeys =
        groupedSelections.keys.filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in world.executorRegistry
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) return groupedSelections

    val resolverDemand =
        unexpandedResolverKeys.fold(selectionForestOf()) { demand, key ->
            demand + world.executorRegistry.resolver(key.field).objectFragment.subselections
        }
    return closeResolverDemand(
        selections = applicableSelections + resolverDemand,
        expanded = expanded + unexpandedResolverKeys,
    )
}

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
private fun Value.Object.dependencyOrder(
    keys: Set<Value.Key>,
    ordered: List<Value.Key> = emptyList(),
): List<Value.Key> {
    if (keys.isEmpty()) return ordered

    val ready =
        keys.filter { key ->
            dependenciesOf(key, keys).isEmpty()
        }.toSet()
    require(ready.isNotEmpty()) {
        "Resolver dependencies on ${type.typeName} contain a cycle"
    }
    return dependencyOrder(
        keys = keys - ready,
        ordered = ordered + ready,
    )
}

/**
 * Returns the unresolved sibling keys demanded by the field resolver for [consumer].
 */
context(world: Assumptions)
private fun Value.Object.dependenciesOf(
    consumer: Value.Key,
    unresolved: Set<Value.Key>,
): Set<Value.Key> {
    if (
        consumer.arguments.argumentsContainErrorValue() ||
        consumer.field !in world.executorRegistry
    ) {
        return emptySet()
    }

    return unresolved
        .filter { sibling ->
            sibling != consumer &&
                consumer.field.demandsFromSibling(sibling)
        }.toSet()
}

/**
 * Returns a one-cell object result for [key] and its merged [fieldSelections].
 */
context(world: Assumptions)
private fun Value.Object.resolveKey(
    key: Value.Key,
    fieldSelections: SelectionForest,
    resolved: EngineResult.Object,
): EngineResult.Object {
    val cell =
        if (key.arguments.argumentsContainErrorValue()) {
            EngineResult.Cell.Error
        } else {
            val subselections =
                fieldSelections.flatMap { selection -> selection.subselections }
            val fieldValue =
                when {
                    key.field.fieldName == "__typename" ->
                        Value.String.of(type.typeName)

                    key.field in world.executorRegistry -> {
                        val resolver = world.executorRegistry.resolver(key.field)
                        // Closure and dependency order put the complete input in this prefix.
                        val input = resolved.materialize(resolver.objectFragment)
                        resolver.function(input, key.arguments)
                    }

                    else -> {
                        // The producing resolver supplies demanded output-selection fields.
                        fieldValues.getValue(key)
                    }
                }
            EngineResult.Cell.of(
                value = fieldValue.resolveValue(subselections),
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}

/**
 * Returns this nullable resolver output resolved for [selections] throughout its value tree.
 */
context(world: Assumptions)
private fun Value.Output?.resolveValue(selections: SelectionForest): EngineResult? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this

        is Value.Object ->
            if (type in world.executorRegistry) {
                resolveNode(selections)
            } else {
                resolve(selections)
            }

        is Value.OutputList ->
            EngineResult.List.of(
                typeExpr = typeExpr,
                cells =
                    values.map { value ->
                        EngineResult.Cell.of(
                            value = value.resolveValue(selections),
                        )
                    },
            )
    }

/**
 * Returns the selected node-resolver result together with this reference's retained `id` bridge.
 */
context(world: Assumptions)
fun Value.Object.resolveNode(selections: SelectionForest): EngineResult.Object {
    val idKey = requireNotNull(world.idKeyOf(type))
    val id = fieldValues.getValue(idKey)
    require(id != Value.Error && id is Value.ID) {
        "Node reference ${type.typeName}/${idKey.field.fieldName} must contain a non-error ID"
    }

    val nodeRef = EngineResult.Object.nodeRef(idKey.field, id)
    val resolverResult =
        world.executorRegistry
            .resolver(type)
            .function(id)
            .resolve(selections)

    return nodeRef.union(resolverResult)
}
