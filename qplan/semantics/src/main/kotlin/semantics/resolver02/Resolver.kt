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
 * Resolves [selections] against an already-produced object value.
 *
 * Before descending, this operation closes the local selection forest under the object fragments
 * of its selected field resolvers. It then resolves sibling keys in dependency order, materializing
 * each resolver's input from the partial result established by its predecessors.
 *
 * A selected field without a registered field resolver remains in the output selection set of the
 * resolver that produced this object. That producing resolver therefore supplies its value. The
 * initial receiver may be an empty Query object because every non-`__typename` Query field has a
 * registered field resolver.
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
                if (key.field in world.executorRegistry) {
                    val resolver = world.executorRegistry.resolver(key.field)
                    // Closure and dependency order put the resolver's complete input in this prefix.
                    val input = resolved.materialize(resolver.objectFragment)
                    resolver.function(input, key.arguments)
                } else {
                    // The producing resolver supplies its demanded output-selection fields.
                    fieldValues.getValue(key)
                }
            EngineResult.Cell.of(
                value = fieldValue.resolve(subselections),
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}

context(world: Assumptions)
private fun Value.Output?.resolve(selections: SelectionForest): EngineResult? =
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
                            value = value.resolve(selections),
                        )
                    },
            )
    }

/**
 * Resolves this ID-bearing node reference, then delegates its resolver value to [resolve].
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
