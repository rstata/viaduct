package semantics.resolver02

import model.Assumptions
import model.EngineResult
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.merge
import model.objectKey
import model.objectKeys
import model.registry.demandsFromSibling
import model.selectionForestOf
import model.union
import semantics.correctresolution.argumentsContainErrorValue
import semantics.materialize
import semantics.resolvePaths
import semantics.resolveValue

/**
 * Resolves [selections] when resolver object fragments may be nonempty but contain no variables.
 * Results are non-selective and may contain more OER nodes than are strictly necessary to resolve
 * the query.
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
    require(resolved.type == type) {
        "Initial result type ${resolved.type.typeName} does not match $type"
    }

    val closedDemand = closeResolverDemand(selections)
    val mergedSelections = closedDemand.merge(type)
    val unresolvedKeys =
        mergedSelections
            .objectKeys(type)
            .filter { key ->
                key !in resolved.keys ||
                    (
                        key.field !in world.resolverRegistry &&
                            key.field.fieldName != "__typename"
                    )
            }.toSet()
    val orderedKeys = dependencyOrder(unresolvedKeys)
    return orderedKeys.fold(resolved) { result, key ->
        val selection = mergedSelections.single(key)
        result.union(resolveKey(selection, result))
    }
}

/**
 * Returns the applicable demand, including all transitive resolver demand on this concrete object.
 */
context(world: Assumptions)
private fun Value.Object.closeResolverDemand(
    selections: SelectionForest,
): SelectionForest =
    type.closeResolverDemand(selections)

context(world: Assumptions)
private fun Schema.ObjectType.closeResolverDemand(
    selections: SelectionForest,
    expanded: Set<Value.ObjectKey> = emptySet(),
): SelectionForest {
    val applicableSelections = selections.merge(this)
    val unexpandedResolverKeys =
        applicableSelections.objectKeys(this).filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in world.resolverRegistry
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) return applicableSelections

    val resolverDemand =
        unexpandedResolverKeys.fold(selectionForestOf()) { demand, key ->
            demand +
                world.resolverRegistry
                    .resolver(key.field)
                    .objectFragment(key.arguments)
                    .subselections
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
    keys: Set<Value.ObjectKey>,
    ordered: List<Value.ObjectKey> = emptyList(),
): List<Value.ObjectKey> {
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
    consumer: Value.ObjectKey,
    unresolved: Set<Value.ObjectKey>,
): Set<Value.ObjectKey> {
    if (
        consumer.arguments.argumentsContainErrorValue() ||
        consumer.field !in world.resolverRegistry
    ) {
        return emptySet()
    }

    return unresolved
        .filter { sibling ->
            sibling != consumer &&
                consumer.demandsFromSibling(sibling)
        }.toSet()
}

/**
 * Returns a one-cell object result for the merged [fieldSelection].
 */
context(world: Assumptions)
private fun Value.Object.resolveKey(
    fieldSelection: Selection,
    resolved: EngineResult.Object,
): EngineResult.Object {
    val key = fieldSelection.objectKey(type)
    val cell =
        if (key.arguments.argumentsContainErrorValue()) {
            EngineResult.Cell.Error
        } else {
            val subselections = fieldSelection.subselections
            val fieldValue =
                when {
                    key.field.fieldName == "__typename" ->
                        Value.String.of(type.typeName)

                    key.field in world.resolverRegistry -> {
                        val resolver = world.resolverRegistry.resolver(key.field)
                        val objectFragment = resolver.objectFragment(key.arguments)
                        // Closure and dependency order put the complete input in this prefix.
                        val input = resolved.materialize(objectFragment)
                        resolver(
                            input = input,
                            arguments = key.arguments,
                        )
                    }

                    else -> {
                        // The producing resolver supplies demanded output-selection fields.
                        fieldValues.getValue(key)
                    }
                }
            EngineResult.Cell.of(
                value =
                    fieldValue
                        .resolveValue(
                            resolverDemand = subselections,
                            selections = null,
                        ).let { resolvedValue ->
                            fieldValue.resolvePaths(resolvedValue) { value, selections, resolved ->
                                value.resolve(selections, resolved)
                            }
                        },
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}
