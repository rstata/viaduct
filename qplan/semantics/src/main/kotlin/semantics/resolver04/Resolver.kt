package semantics.resolver04

import model.Assumptions
import model.EngineResult
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.registry.availableDemand
import model.selectionForestOf
import model.union
import semantics.correctresolution.argumentsContainErrorValue
import semantics.correctresolution.concreteObjectKey
import semantics.instantiateVariables
import semantics.materialize
import semantics.readVariable
import semantics.variables
import java.util.IdentityHashMap

/**
 * Returns the result for [selections] and all transitive resolver demand on this concrete object.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object =
    context(ResolutionSources()) {
        resolve(
            selections = selections,
            resolved = EngineResult.Object.of(type, emptyMap()),
        )
    }

/**
 * [ambientSelections] is already-known demand on this OER while a variable provider is being
 * resolved. Demand closure strictly includes matching concrete-key occurrences and speculatively
 * includes variable-free child demand from symbolic occurrences of the same field. A provider
 * dependency and an operation or sibling requirement that later converge on one cell can therefore
 * contribute to one selective resolver application without requiring a partial output at a
 * different argument tuple to contain that speculative demand.
 */
context(world: Assumptions, sources: ResolutionSources)
private fun Value.Object.resolve(
    selections: SelectionForest,
    resolved: EngineResult.Object,
    ambientSelections: SelectionForest? = null,
    includeAmbientRoots: Boolean = false,
): EngineResult.Object {
    require(resolved.type == type) {
        "Initial result type ${resolved.type.typeName} does not match $type"
    }

    val closure =
        closeResolverDemand(
            selections = selections,
            resolved = resolved,
            ambientSelections = ambientSelections,
            includeAmbientRoots = includeAmbientRoots,
        )
    val selectionsByKey =
        closure.selections.groupBy { selection -> selection.concreteObjectKey(type) }
    val requiredSelectionsByKey =
        closure.requiredSelections.groupBy { selection -> selection.concreteObjectKey(type) }
    val speculativeSelectionsByKey =
        closure.speculativeSelections.groupBy { selection -> selection.concreteObjectKey(type) }
    val widened =
        (selectionsByKey.keys intersect closure.resolved.keys).fold(closure.resolved) {
                result,
                key,
            ->
            sources.union(
                result,
                result.resolveExistingKey(
                    key = key,
                    requiredFieldSelections =
                        requiredSelectionsByKey[key] ?: selectionForestOf(),
                    speculativeFieldSelections =
                        speculativeSelectionsByKey[key] ?: selectionForestOf(),
                ),
            )
        }
    val orderedKeys = dependencyOrder(selectionsByKey.keys - widened.keys, widened)
    return orderedKeys.fold(widened) { result, key ->
        sources.union(
            result,
            resolveKey(
                key = key,
                requiredFieldSelections =
                    requiredSelectionsByKey[key] ?: selectionForestOf(),
                speculativeFieldSelections =
                    speculativeSelectionsByKey[key] ?: selectionForestOf(),
                resolved = result,
            ),
        )
    }.also { result -> sources.remember(result, this) }
}

/**
 * Returns the applicable demand, including all transitive resolver demand on this concrete object.
 */
context(world: Assumptions, sources: ResolutionSources)
private fun Value.Object.closeResolverDemand(
    selections: SelectionForest,
    resolved: EngineResult.Object,
    expanded: Set<Value.Key> = emptySet(),
    ambientSelections: SelectionForest? = null,
    includeAmbientRoots: Boolean = false,
): DemandClosure {
    val requiredSelections =
        selections.filter { selection -> type in selection.possibleTypes }
    val speculativeSelections =
        if (includeAmbientRoots) {
            (ambientSelections?.passiveDemand() ?: selectionForestOf())
                .filter { selection -> type in selection.possibleTypes }
        } else {
            selectionForestOf()
        }
    val directlyApplicableSelections = requiredSelections + speculativeSelections
    val directlyDemandedKeys =
        directlyApplicableSelections
            .groupBy { selection -> selection.concreteObjectKey(type) }
            .keys
    val speculativeDemand =
        speculativeSelections +
            ambientSelections.matchingAmbientDemand(directlyDemandedKeys)
    val applicableSelections = requiredSelections + speculativeDemand
    val requiredKeys =
        requiredSelections
            .groupBy { selection -> selection.concreteObjectKey(type) }
            .keys
    if (world.noTransitiveDemand && expanded.isNotEmpty()) {
        return DemandClosure(
            selections = applicableSelections,
            requiredSelections = requiredSelections,
            speculativeSelections = speculativeDemand,
            resolved = resolved,
        )
    }
    val unexpandedResolverKeys =
        requiredKeys.filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in world.executorRegistry
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) {
        return DemandClosure(
            selections = applicableSelections,
            requiredSelections = requiredSelections,
            speculativeSelections = speculativeDemand,
            resolved = resolved,
        )
    }

    val fragments =
        unexpandedResolverKeys.map { key ->
            world.executorRegistry
                .resolver(key.field)
                .objectFragment(key.arguments)
        }
    // Provider paths and concrete transitive requirements must share one selective demand closure.
    val ambientFragmentDemand =
        fragments.fold(selectionForestOf()) { demand, fragment ->
            demand + fragment.subselections
        }.withExtendedResolverDemand()
    val resolvedVariables =
        resolveVariables(
            variables =
                fragments.fold(emptySet()) { variables, fragment ->
                    variables + fragment.variables()
                },
            resolved = resolved,
            ambientSelections =
                requiredSelections.withExtendedResolverDemand() +
                    speculativeDemand +
                    ambientFragmentDemand,
        )
    val resolverDemand =
        fragments.fold(selectionForestOf()) { demand, fragment ->
            demand +
                fragment
                    .instantiateVariables(resolvedVariables.variableValues)
                    .subselections
        }
    return closeResolverDemand(
        selections = requiredSelections + resolverDemand,
        resolved = resolvedVariables,
        expanded = expanded + unexpandedResolverKeys,
        ambientSelections = ambientSelections,
        includeAmbientRoots = includeAmbientRoots,
    )
}

private data class DemandClosure(
    val selections: SelectionForest,
    val requiredSelections: SelectionForest,
    val speculativeSelections: SelectionForest,
    val resolved: EngineResult.Object,
)

context(world: Assumptions, sources: ResolutionSources)
private fun Value.Object.resolveVariables(
    variables: Set<Value.Variable>,
    resolved: EngineResult.Object,
    ambientSelections: SelectionForest,
): EngineResult.Object =
    variables.fold(resolved) { result, variable ->
        if (variable in result.variableValues) {
            result
        } else {
            val provider = world.executorRegistry.variable(variable)
            val withDependencies =
                resolveVariables(
                    variables = provider.variables(),
                    resolved = result,
                    ambientSelections = ambientSelections,
                )
            val instantiated = provider.instantiateVariables(withDependencies.variableValues)
            val extendedProviderDemand =
                selectionForestOf(instantiated).withExtendedResolverDemand()
            val providerDemand =
                selectionForestOf(instantiated) +
                    extendedProviderDemand.withoutVariableKeys()
            val withProvider =
                resolve(
                    selections = providerDemand,
                    resolved = withDependencies,
                    ambientSelections =
                        ambientSelections +
                            extendedProviderDemand,
                )
            val value = withProvider.readVariable(instantiated)
            sources.union(
                withProvider,
                EngineResult.Object.of(
                    type = type,
                    cells = emptyMap(),
                    variableValues = mapOf(variable to value),
                ),
            )
        }
    }

context(world: Assumptions)
private fun SelectionForest?.matchingAmbientDemand(
    directlyDemandedKeys: Set<Value.Key>,
): SelectionForest =
    this?.flatMap { selection ->
        directlyDemandedKeys.fold(selectionForestOf()) { demand, key ->
            demand + selection.matchingAmbientDemand(key)
        }
    } ?: selectionForestOf()

context(world: Assumptions)
private fun Selection.matchingAmbientDemand(key: Value.Key): SelectionForest {
    val objectType = key.field.containingType as Schema.ObjectType
    if (objectType !in possibleTypes) return selectionForestOf()
    val concreteKey = concreteObjectKey(objectType)
    if (concreteKey.field != key.field) return selectionForestOf()
    if (concreteKey.arguments == key.arguments) {
        return selectionForestOf(withoutVariableSubselections())
    }
    if (this.key.arguments.variables().isEmpty()) {
        return selectionForestOf()
    }
    return selectionForestOf(
        Selection.of(
            key =
                Value.Key.of(
                    field = this.key.field,
                    arguments = key.arguments.fieldValues,
                ),
            nominalType = nominalType,
            possibleTypes = possibleTypes,
            subselections = subselections.withoutVariableKeys(),
        ),
    )
}

private fun Selection.withoutVariableSubselections(): Selection =
    Selection.of(
        key = key,
        nominalType = nominalType,
        possibleTypes = possibleTypes,
        subselections = subselections.withoutVariableKeys(),
    )

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
private fun Value.Object.dependencyOrder(
    keys: Set<Value.Key>,
    resolved: EngineResult.Object,
    ordered: List<Value.Key> = emptyList(),
): List<Value.Key> {
    if (keys.isEmpty()) return ordered

    val ready =
        keys.filter { key ->
            dependenciesOf(key, keys, resolved).isEmpty()
        }.toSet()
    require(ready.isNotEmpty()) {
        "Resolver dependencies on ${type.typeName} contain a cycle"
    }
    return dependencyOrder(
        keys = keys - ready,
        resolved = resolved,
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
    resolved: EngineResult.Object,
): Set<Value.Key> {
    if (
        consumer.arguments.argumentsContainErrorValue() ||
        consumer.field !in world.executorRegistry
    ) {
        return emptySet()
    }

    val selections =
        world.executorRegistry
            .resolver(consumer.field)
            .objectFragment(consumer.arguments)
            .instantiateVariables(resolved.variableValues)
            .subselections
    return unresolved.filter { sibling ->
        sibling != consumer &&
            !selections.all { selection ->
                type !in selection.possibleTypes ||
                    selection.concreteObjectKey(type) != sibling
            }
    }.toSet()
}

/**
 * Returns a one-cell object result for [key] and its merged [fieldSelections].
 */
context(world: Assumptions, sources: ResolutionSources)
private fun Value.Object.resolveKey(
    key: Value.Key,
    requiredFieldSelections: SelectionForest,
    speculativeFieldSelections: SelectionForest,
    resolved: EngineResult.Object,
): EngineResult.Object {
    val cell =
        if (key.arguments.argumentsContainErrorValue()) {
            EngineResult.Cell.Error
        } else {
            val requiredSubselections =
                requiredFieldSelections.flatMap { selection -> selection.subselections }
            val speculativeSubselections =
                speculativeFieldSelections.flatMap { selection -> selection.subselections }
            val fieldOutput =
                when {
                    key.field.fieldName == "__typename" ->
                        Value.String.of(type.typeName).let { value ->
                            ResolverOutput(value, value)
                        }

                    key.field in world.executorRegistry -> {
                        val resolver = world.executorRegistry.resolver(key.field)
                        val objectFragment =
                            resolver
                                .objectFragment(key.arguments)
                                .instantiateVariables(resolved.variableValues)
                        // Closure and dependency order put the complete input in this prefix.
                        val input = resolved.materialize(objectFragment)
                        resolver.resolveWithSource(
                            input = input,
                            arguments = key.arguments,
                            transitiveDemand =
                                requiredSubselections.withExtendedResolverDemand(),
                            speculativeDemand = speculativeSubselections,
                        ).let { output ->
                            ResolverOutput(output.source, output.projected)
                        }
                    }

                    else -> {
                        // The producing resolver supplies demanded output-selection fields.
                        fieldValues.getValue(key).let { value ->
                            ResolverOutput(value, value)
                        }
                    }
                }
            val availableSubselections =
                fieldOutput.projected.availableDemand(speculativeSubselections)
            EngineResult.Cell.of(
                value =
                    fieldOutput.projected.resolveValue(
                        selections = requiredSubselections,
                        ambientSelections = availableSubselections,
                        source = fieldOutput.source,
                    ),
            )
        }

    return EngineResult.Object.of(type, mapOf(key to cell))
}

/**
 * Returns the already-present [key] with any newly demanded descendants added to its value.
 */
context(world: Assumptions, sources: ResolutionSources)
private fun EngineResult.Object.resolveExistingKey(
    key: Value.Key,
    requiredFieldSelections: SelectionForest,
    speculativeFieldSelections: SelectionForest,
): EngineResult.Object {
    val existing = fetch(key)
    val requiredSubselections =
        requiredFieldSelections.flatMap { selection -> selection.subselections }
    val speculativeSubselections =
        speculativeFieldSelections.flatMap { selection -> selection.subselections }
    val availableSubselections =
        sources.output(existing.value).availableDemand(speculativeSubselections)
    return EngineResult.Object.of(
        type = type,
        cells =
            mapOf(
                key to
                    EngineResult.Cell.of(
                        value =
                            existing.value.resolveAdditional(
                                selections = requiredSubselections,
                                ambientSelections = availableSubselections,
                            ),
                        check = existing.check,
                    ),
            ),
    )
}

/**
 * Adds [selections] to an existing value without reapplying the resolver that produced it.
 */
context(world: Assumptions, sources: ResolutionSources)
private fun EngineResult?.resolveAdditional(
    selections: SelectionForest,
    ambientSelections: SelectionForest,
): EngineResult? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> {
            require(selections.isEmpty() && ambientSelections.isEmpty()) {
                "Cannot apply subselections to a simple value $this"
            }
            this
        }

        is EngineResult.Object ->
            (sources.source(this) ?: asValueObject()).resolve(
                selections = selections,
                resolved = this,
                ambientSelections = ambientSelections,
                includeAmbientRoots = true,
            )

        is EngineResult.List ->
            EngineResult.List.of(
                typeExpr = typeExpr,
                cells =
                    map { cell ->
                        EngineResult.Cell.of(
                            value =
                                cell.value.resolveAdditional(
                                    selections,
                                    ambientSelections,
                                ),
                            check = cell.check,
                        )
                    },
            )
    }

/**
 * Exposes the values already present in this OER as the passive source for additional resolution.
 */
private fun EngineResult.Object.asValueObject(): Value.Object =
    Value.Object.of(
        type = type,
        fields = cells.mapValues { (_, cell) -> cell.value.asOutputValue() },
    )

private fun EngineResult?.asOutputValue(): Value.Output? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.Object -> asValueObject()
        is EngineResult.List ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values = map { cell -> cell.value.asOutputValue() },
            )
    }

/**
 * Returns this nullable resolver output resolved for [selections] throughout its value tree.
 */
context(world: Assumptions, sources: ResolutionSources)
private fun Value.Output?.resolveValue(
    selections: SelectionForest,
    ambientSelections: SelectionForest = selectionForestOf(),
    source: Value.Output? = this,
): EngineResult? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> {
            require(selections.isEmpty() && ambientSelections.isEmpty()) {
                "Cannot apply subselections to a simple value $this"
            }
            this
        }

        is Value.Object -> {
            val sourceObject = source as? Value.Object ?: this
            sourceObject.resolve(
                selections = selections,
                resolved = EngineResult.Object.of(type, emptyMap()),
                ambientSelections = ambientSelections,
                includeAmbientRoots = true,
            )
        }

        is Value.OutputList -> {
            val sourceValues =
                (source as? Value.OutputList)
                    ?.takeIf { it.values.size == values.size }
                    ?.values
            EngineResult.List.of(
                typeExpr = typeExpr,
                cells =
                    values.mapIndexed { index, value ->
                        EngineResult.Cell.of(
                            value =
                                value.resolveValue(
                                    selections,
                                    ambientSelections,
                                    sourceValues?.get(index) ?: value,
                                ),
                        )
                    },
            )
        }
    }

private data class ResolverOutput(
    val source: Value.Output?,
    val projected: Value.Output?,
)

private class ResolutionSources {
    private val objects = IdentityHashMap<EngineResult.Object, Value.Object>()

    fun source(result: EngineResult.Object): Value.Object? = objects[result]

    fun output(result: EngineResult?): Value.Output? =
        when (result) {
            null -> null
            Value.Error -> Value.Error
            is Value.Simple -> result
            is EngineResult.Object -> source(result) ?: result.asValueObject()
            is EngineResult.List ->
                Value.OutputList.of(
                    typeExpr = result.typeExpr,
                    values = result.map { cell -> output(cell.value) },
                )
        }

    fun remember(
        result: EngineResult?,
        source: Value.Output?,
    ) {
        when {
            result is EngineResult.Object && source is Value.Object -> {
                objects[result] = source
                result.cells.forEach { (key, cell) ->
                    if (key in source.fieldValues) {
                        remember(cell.value, source.fieldValues.getValue(key))
                    }
                }
            }

            result is EngineResult.List && source is Value.OutputList &&
                result.size == source.values.size ->
                result.indices.forEach { index ->
                    remember(result[index].value, source.values[index])
                }
        }
    }

    fun union(
        left: EngineResult.Object,
        right: EngineResult.Object,
    ): EngineResult.Object =
        left.union(right).also { result ->
            inherit(result, listOf(left, right))
        }

    private fun inherit(
        result: EngineResult?,
        antecedents: List<EngineResult?>,
    ) {
        when (result) {
            is EngineResult.Object -> {
                antecedents
                    .filterIsInstance<EngineResult.Object>()
                    .firstNotNullOfOrNull(::source)
                    ?.let { source -> objects[result] = source }
                result.cells.forEach { (key, cell) ->
                    inherit(
                        cell.value,
                        antecedents
                            .filterIsInstance<EngineResult.Object>()
                            .mapNotNull { antecedent ->
                                antecedent.cells[key]?.value
                            },
                    )
                }
            }

            is EngineResult.List ->
                result.indices.forEach { index ->
                    inherit(
                        result[index].value,
                        antecedents
                            .filterIsInstance<EngineResult.List>()
                            .filter { antecedent -> antecedent.size == result.size }
                            .map { antecedent -> antecedent[index].value },
                    )
                }

            else -> Unit
        }
    }
}

/**
 * Adds the precomputed input requirements of every resolver occurrence in this demand.
 *
 * Each resolver's extended fragment is rooted at its occurrence's containing object. Recursing
 * through subselections retains the path and type guards that locate nested occurrences.
 */
context(world: Assumptions)
private fun SelectionForest.withExtendedResolverDemand(): SelectionForest =
    flatMap { selection ->
        val nestedDemand = selection.subselections.withExtendedResolverDemand()
        val rootedSelection =
            Selection.of(
                key = selection.key,
                nominalType = selection.nominalType,
                possibleTypes = selection.possibleTypes,
                subselections = nestedDemand,
            )
        val resolverDemand =
            selection.possibleTypes.fold(selectionForestOf()) { demand, possibleType ->
                val key = selection.concreteObjectKey(possibleType)
                if (
                    key.arguments.argumentsContainErrorValue() ||
                    key.field !in world.executorRegistry
                ) {
                    demand
                } else {
                    val resolver = world.executorRegistry.resolver(key.field)
                    val fragment =
                        if (world.noTransitiveDemand) {
                            resolver.objectFragment(key.arguments)
                        } else {
                            resolver.extendedFragment(key.arguments)
                        }
                    demand + fragment.subselections
                }
            }
        selectionForestOf(rootedSelection) + resolverDemand
    }

/**
 * Retains every selection whose own key is concrete, recursively omitting symbolic descendants.
 */
private fun SelectionForest.withoutVariableKeys(): SelectionForest =
    flatMap { selection ->
        if (selection.key.arguments.variables().isNotEmpty()) {
            selectionForestOf()
        } else {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    nominalType = selection.nominalType,
                    possibleTypes = selection.possibleTypes,
                    subselections = selection.subselections.withoutVariableKeys(),
                ),
            )
        }
    }

/**
 * Returns the portion of this demand that can be retained without applying a field resolver.
 */
context(world: Assumptions)
private fun SelectionForest.passiveDemand(): SelectionForest =
    flatMap { selection ->
        val passiveTypes =
            selection.possibleTypes.filterTo(linkedSetOf()) { possibleType ->
                val field =
                    world.schema.field(
                        possibleType.typeName,
                        selection.key.field.fieldName,
                    )
                !world.behavioral(field)
            }
        if (passiveTypes.isEmpty()) {
            selectionForestOf()
        } else {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    nominalType = selection.nominalType,
                    possibleTypes = passiveTypes,
                    subselections = selection.subselections.passiveDemand(),
                ),
            )
        }
    }
