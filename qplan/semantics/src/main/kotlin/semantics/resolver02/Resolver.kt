package semantics.resolver02

import model.Assumptions
import model.EngineResult
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.registry.Resolver
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
    val selectionsByKey =
        closedDemand.groupBy { selection -> selection.concreteObjectKey(type) }
    val orderedKeys = dependencyOrder(selectionsByKey.keys - resolved.keys)
    return orderedKeys.fold(resolved) { result, key ->
        result.union(resolveKey(key, selectionsByKey.getValue(key), result))
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
    expanded: Set<Value.Key> = emptySet(),
): SelectionForest {
    val applicableSelections =
        selections.filter { selection -> this in selection.possibleTypes }
    val groupedSelections =
        applicableSelections.groupBy { selection -> selection.concreteObjectKey(this) }
    if (world.noTransitiveDemand && expanded.isNotEmpty()) return applicableSelections
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
                consumer.demandsFromSibling(sibling)
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
                        val objectFragment = resolver.objectFragment(key.arguments)
                        // Closure and dependency order put the complete input in this prefix.
                        val input = resolved.materialize(objectFragment)
                        resolver.resolve(
                            input = input,
                            arguments = key.arguments,
                            transitiveDemand =
                                subselections + resolver.outputSelectionForest(subselections),
                        )
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

        is Value.Object -> resolve(selections)

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
 * Returns this field resolver's finite output selection forest relative to [demand].
 *
 * A field resolver owns every non-behavioral field of its result. Ownership is unfolded completely
 * along acyclic paths. At each concrete object, locally closed resolver demand bounds further
 * unfolding when a path returns to an ancestor type, so a recursive output selection set remains
 * finite.
 */
context(world: Assumptions)
private fun Resolver.Field.outputSelectionForest(demand: SelectionForest): SelectionForest =
    demand.outputSelectionForest()

context(world: Assumptions)
private fun SelectionForest.outputSelectionForest(): SelectionForest =
    groupBy(Selection::possibleTypes)
        .keys
        .flatten()
        .toSet()
        .fold(selectionForestOf()) { result, possibleType ->
            result +
                possibleType.outputSelectionForest(
                    demand = this,
                    ancestors = emptySet(),
                )
        }

context(world: Assumptions)
private fun Schema.ObjectType.outputSelectionForest(
    demand: SelectionForest,
    ancestors: Set<Schema.ObjectType>,
): SelectionForest {
    val closedDemand = closeResolverDemand(demand)
    return fields.values.fold(selectionForestOf()) { result, field ->
        if (world.behavioral(field)) {
            result
        } else {
            val nestedDemand =
                closedDemand
                    .filter { selection ->
                        this in selection.possibleTypes &&
                            selection.key.field.fieldName == field.fieldName
                    }
                    .flatMap(Selection::subselections)
            val outputType = field.typeExpr.baseType
            val nestedOutputSelectionForest =
                if (outputType is Schema.CompositeType) {
                    outputType.possibleTypes.fold(selectionForestOf()) { nestedResult, possibleType ->
                        val recursive =
                            possibleType == this || possibleType in ancestors
                        if (recursive && nestedDemand.isEmpty()) {
                            nestedResult
                        } else {
                            nestedResult +
                                possibleType.outputSelectionForest(
                                    demand = nestedDemand,
                                    ancestors = ancestors + this,
                                )
                        }
                    }
                } else {
                    selectionForestOf()
                }
            result +
                selectionForestOf(
                    Selection.of(
                        key = Value.Key.of(field, emptyMap()),
                        nominalType = this,
                        possibleTypes = setOf(this),
                        subselections = nestedOutputSelectionForest,
                    ),
                )
        }
    }
}
