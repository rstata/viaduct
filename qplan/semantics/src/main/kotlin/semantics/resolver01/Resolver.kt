package semantics.resolver01

import model.Assumptions
import model.EngineResult
import model.Schema
import model.SelectionForest
import model.Value
import model.registry.demandsFromSibling
import model.selectionForestOf
import model.union
import semantics.correctresolution.argumentsContainErrorValue
import semantics.correctresolution.concreteObjectKey
import semantics.materialize
import semantics.resolvePaths
import semantics.resolveValue

/**
 * Resolves [selections] against an already-produced object value.
 *
 * Resolver01 closes and orders exact local field-resolver input demand. Its ordinary test domain
 * still gives source field resolvers empty object fragments; fixture-generated node loaders use
 * synthetic sibling ID bridge fields and therefore require this generic local field dependency.
 * Every supplied or resolver-introduced selection applicable at an object visited by this
 * operation must contain no [Value.Variable] in its key arguments.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object =
    resolve(
        selections = selections,
        resolved = EngineResult.Object.of(type, emptyMap()),
    )

context(world: Assumptions)
private fun Value.Object.resolve(
    selections: SelectionForest,
    resolved: EngineResult.Object,
): EngineResult.Object {
    val closedDemand = type.closeResolverDemand(selections)
    val selectionsByKey =
        closedDemand.groupBy { selection -> selection.concreteObjectKey(type) }
    val orderedKeys = dependencyOrder(selectionsByKey.keys - resolved.keys)
    return orderedKeys.fold(resolved) { result, key ->
        result.union(resolveKey(key, selectionsByKey.getValue(key), result))
    }
}

context(world: Assumptions)
private fun Schema.ObjectType.closeResolverDemand(
    selections: SelectionForest,
    expanded: Set<Value.Key> = emptySet(),
): SelectionForest {
    val applicableSelections =
        selections.filter { selection -> this in selection.possibleTypes }
    val groupedSelections =
        applicableSelections.groupBy { selection -> selection.concreteObjectKey(this) }
    val unexpandedResolverKeys =
        groupedSelections.keys.filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in world.executorRegistry
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) return applicableSelections

    val resolverDemand =
        unexpandedResolverKeys.fold(selectionForestOf()) { demand, key ->
            demand +
                world.executorRegistry
                    .resolver(key.field)
                    .objectFragment(key.arguments)
                    .subselections
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
                consumer.demandsFromSibling(sibling)
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
                when {
                    key.field.fieldName == "__typename" ->
                        Value.String.of(type.typeName)

                    key.field in world.executorRegistry -> {
                        val resolver = world.executorRegistry.resolver(key.field)
                        val objectFragment = resolver.objectFragment(key.arguments)
                        resolver.tenantResolve(
                            input = resolved.materialize(objectFragment),
                            arguments = key.arguments,
                            selections = subselections,
                        )
                    }

                    else -> fieldValues.getValue(key)
                }
            EngineResult.Cell.of(
                value =
                    fieldValue.resolveValue(subselections).let { resolvedValue ->
                        fieldValue.resolvePaths(resolvedValue) { value, selections, resolved ->
                            value.resolve(selections, resolved)
                        }
                    },
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}
