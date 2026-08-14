package semantics

import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.Promise
import model.Schema
import model.Selection
import model.SelectionForest
import model.TypeExpr
import model.Value
import model.concatenateSelectionForests
import model.fetchBindings
import model.flatMapToSelectionForest
import model.groundKey
import model.instantiateBindings
import model.merge
import model.objectKey
import model.selectionForestOf
import model.stampedVariables
import model.toSelectionForest
import model.usedVariables
import model.registry.StampedObjectPathDefinition
import semantics.correctresolution.argumentsContainErrorValue

/**
 * Resolves open demand through one structured coroutine tree.
 *
 * Each object orchestrator is the sole consumer of variables used by keys in its OER. Open
 * selections suspend independently, then converge through one exact-key slot map.
 */
context(world: Assumptions, runtimeSupport: RuntimeSupport)
internal suspend fun Value.Object.coroutineResolveOpen(
    selections: SelectionForest,
): EngineResult.Object {
    val result =
        EngineResult.Object.of(
            type = type,
            mutable = true,
        )

    coroutineScope {
        val runtime = OpenCoroutineRuntime(this)
        runtime.createOrchestrator(
            path = emptyList(),
            source = this@coroutineResolveOpen,
            target = result,
            initialDemand = selections,
        )
    }

    return result
}

private class OpenCoroutineRuntime(
    val scope: CoroutineScope,
) {
    private val orchestrators =
        IdentityHashMap<EngineResult.Object, OpenObjectOrchestrator>()

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    fun createOrchestrator(
        path: List<PathComponent>,
        source: Value.Object,
        target: EngineResult.Object,
        initialDemand: SelectionForest,
    ): OpenObjectOrchestrator {
        val existing = orchestrators[target]
        if (existing != null) {
            existing.requireOccurrence(path, source)
            existing.addDemand(initialDemand)
            return existing
        }
        val orchestrator =
            OpenObjectOrchestrator(
                runtime = this,
                path = path,
                source = source,
                target = target,
                initialDemand = initialDemand,
            )
        orchestrators[target] = orchestrator
        orchestrator.start()
        return orchestrator
    }

    fun orchestrator(target: EngineResult.Object): OpenObjectOrchestrator =
        checkNotNull(orchestrators[target]) {
            "No coroutine orchestrator registered for ${target.type.typeName} OER"
        }
}

private class OpenObjectOrchestrator(
    private val runtime: OpenCoroutineRuntime,
    val path: List<PathComponent>,
    private val source: Value.Object,
    private val target: EngineResult.Object,
    private val initialDemand: SelectionForest,
) {
    private val exactSelections =
        linkedMapOf<Value.GroundKey, ObjectSelection>()
    private val slots =
        linkedMapOf<Value.GroundKey, OpenSlot>()
    private val expanded =
        linkedSetOf<List<PathComponent>>()
    private val acceptedDemand =
        linkedSetOf<OpenSelectionSignature>()
    private lateinit var projectionEnvelope: ObjectSelectionForest

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    fun start() {
        projectionEnvelope = source.type.projectionEnvelope(initialDemand)
        addDemand(initialDemand)
    }

    fun requireOccurrence(
        expectedPath: List<PathComponent>,
        expectedSource: Value.Object,
    ) {
        check(path == expectedPath && source === expectedSource) {
            "One OER was associated with multiple source occurrences"
        }
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    fun addDemand(demand: SelectionForest) {
        demand.merge(source.type).forEach { selection ->
            selection as ObjectSelection
            if (!acceptedDemand.add(selection.signature())) {
                return@forEach
            }
            val variables = selection.key.stampedVariables()
            check(
                selection.key.arguments.usedVariables().none {
                    it is Value.Variable.Template
                },
            ) {
                "Coroutine demand contains an unstamped variable: ${selection.key}"
            }
            if (variables.all(world::isBound)) {
                accept(
                    selection = selection.groundImmediately(),
                    propagateExisting = true,
                )
            } else {
                runtime.scope.launch(start = CoroutineStart.DEFAULT) {
                    accept(
                        selection = selection.fetchGroundSelection(),
                        propagateExisting = true,
                    )
                }
            }
        }
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    suspend fun materialize(
        selections: SelectionForest,
        reader: List<PathComponent>,
    ): Value.Object {
        val grounded = groundSelections(selections)
        val fields = linkedMapOf<Value.GroundKey, Value.Output?>()
        grounded.byGroundKey().forEach { (key, selection) ->
            val slot =
                accept(
                    selection = selection,
                    propagateExisting = false,
                )
            runtimeSupport.cycleCheck(reader, slot.cell)
            fields[key] =
                slot.promise.await().materialize(
                    selections = selection.subselections,
                    reader = reader,
                )
        }
        return Value.Object.of(source.type, fields)
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    private suspend fun groundSelections(
        selections: SelectionForest,
    ): ObjectSelectionForest {
        val byKey =
            linkedMapOf<Value.GroundKey, MutableList<ObjectSelection>>()
        val openSelections = mutableListOf<ObjectSelection>()
        selections.merge(source.type).forEach { selection ->
            openSelections += selection as ObjectSelection
        }
        for (selection in openSelections) {
            val grounded = selection.fetchGroundSelection()
            byKey
                .getOrPut(grounded.groundKey(), ::mutableListOf)
                .add(grounded)
        }
        return ObjectSelectionForest.of(
            source.type,
            byKey.map { (key, occurrences) ->
                ObjectSelection.of(
                    key = key,
                    possibleTypes = setOf(source.type),
                    subselections =
                        occurrences
                            .flatMapToSelectionForest(ObjectSelection::subselections)
                            .coalesceEquivalentSelections(),
                )
            },
        )
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    private fun accept(
        selection: ObjectSelection,
        propagateExisting: Boolean,
    ): OpenSlot {
        val key = selection.groundKey()
        val previous = exactSelections[key]
        val merged =
            if (previous == null) {
                selection
            } else {
                ObjectSelection.of(
                    key = key,
                    possibleTypes = setOf(source.type),
                    subselections =
                        (previous.subselections + selection.subselections)
                            .coalesceEquivalentSelections(),
                )
            }
        exactSelections[key] = merged

        val existing = slots[key]
        if (existing != null) {
            if (propagateExisting && !selection.subselections.isEmpty()) {
                runtime.scope.launch(start = CoroutineStart.DEFAULT) {
                    propagateDemand(
                        value = existing.promise.await(),
                        demand = selection.subselections,
                    )
                }
            }
            return existing
        }

        val cell = target.reserveCell(key)
        val promise =
            if (cell.isValueSet()) {
                cell.getValue()
            } else {
                cell.createValuePromise()
                runtimeSupport.registerWriter(
                    cell = cell,
                    writer = path + key,
                )
                cell.getValue()
            }
        val slot = OpenSlot(key, cell)
        slots[key] = slot

        if (!target.isCellSet(key)) {
            error("Installed value promise is not visible for $key")
        }
        if (!promise.isCompleted) {
            expandResolverOccurrence(key)
            runtime.scope.launch(start = CoroutineStart.DEFAULT) {
                resolve(slot)
            }
        } else if (!selection.subselections.isEmpty()) {
            runtime.scope.launch(start = CoroutineStart.DEFAULT) {
                propagateDemand(
                    value = promise.await(),
                    demand = selection.subselections,
                )
            }
        }
        return slot
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    private fun expandResolverOccurrence(key: Value.GroundKey) {
        if (
            key.arguments.argumentsContainErrorValue() ||
            key.field !in world.resolverRegistry
        ) {
            return
        }
        val coordinate = path + key
        check(expanded.add(coordinate)) {
            "Resolver occurrence expanded more than once: $coordinate"
        }
        listOf(key).bindFromArguments(path)
        val resolver = world.resolverRegistry.resolver(key.field)
        val definitions = resolver.stampedPathVarDefinitions(coordinate)
        definitions.forEach { definition ->
            world.declareBinding(definition.variable)
        }
        addDemand(resolver.stampVars(coordinate))
        definitions.forEach { definition ->
            runtime.scope.launch(start = CoroutineStart.DEFAULT) {
                val value = readProvider(definition, coordinate)
                world.completeBinding(definition.variable, value)
            }
        }
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    private suspend fun readProvider(
        definition: StampedObjectPathDefinition,
        reader: List<PathComponent>,
    ): Value.Input? {
        var current = this
        definition.path.forEachIndexed { index, openKey ->
            val value = current.await(openKey, reader)
            if (value == null) return null
            if (value == Value.Error) return Value.Error
            if (index == definition.path.lastIndex) {
                return value.toProviderInput()
            }
            current =
                runtime.orchestrator(
                    value as? EngineResult.Object
                        ?: error("Provider path crossed a non-object at $openKey"),
                )
        }
        error("Provider path must be nonempty")
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    private suspend fun await(
        openKey: Value.Key,
        reader: List<PathComponent>,
    ): EngineResult? {
        val selection =
            Selection.of(
                key = openKey,
                possibleTypes = setOf(source.type),
                subselections = selectionForestOf(),
            ).objectSelection(source.type)
                .fetchGroundSelection()
        val key = selection.groundKey()
        val slot =
            accept(
                selection = selection,
                propagateExisting = false,
            )
        runtimeSupport.cycleCheck(reader, slot.cell)
        return slot.promise.await()
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    private suspend fun resolve(slot: OpenSlot) {
        val key = slot.key
        when {
            key.arguments.argumentsContainErrorValue() ->
                slot.complete(Value.Error, Value.Error)
            key.field.fieldName == "__typename" -> {
                slot.promise.complete(Value.String.of(source.type.typeName))
                slot.cell.setAccessAccepted(Value.Boolean.of(true))
            }
            else -> {
                val selection = selectionForLaunch(key)
                val completion = runtimeSupport.complete(selection.subselections)
                val resolutionSelections = completion.selections
                val fieldValue =
                    if (key.field in world.resolverRegistry) {
                        val resolver = world.resolverRegistry.resolver(key.field)
                        val coordinate = path + key
                        val input =
                            materialize(
                                selections = resolver.stampVars(coordinate),
                                reader = coordinate,
                            )
                        when {
                            completion.retainCompleteOutput ->
                                resolver.completeOutput(
                                    input = input,
                                    arguments = key.arguments,
                                    selections = resolutionSelections,
                                )
                            world.selectiveResolvers ->
                                resolver(
                                    input = input,
                                    arguments = key.arguments,
                                    selections = resolutionSelections,
                                )
                            else ->
                                resolver(
                                    input = input,
                                    arguments = key.arguments,
                                )
                        }
                    } else {
                        require(!world.selectiveResolvers) {
                            "Passive key found ($key)."
                        }
                        source.fieldValues.getValue(key)
                    }
                val resolvedValue =
                    fieldValue.resolveValue(
                        path = path + key,
                        resolverDemand = resolutionSelections,
                        retainCompleteOutput = completion.retainCompleteOutput,
                    )

                resolvedValue.objectOccurrences.forEach { occurrence ->
                    runtime.createOrchestrator(
                        path = occurrence.path,
                        source = occurrence.source,
                        target = occurrence.target,
                        initialDemand = occurrence.selections,
                    )
                }
                slot.complete(
                    resolvedValue.engineResult,
                    Value.Boolean.of(true),
                )
            }
        }
    }

    context(world: Assumptions)
    private fun selectionForLaunch(key: Value.GroundKey): ObjectSelection {
        val exact = exactSelections.getValue(key)
        val conservative =
            projectionEnvelope
                .byKey()
                .values
                .filter { selection -> selection.mayContributeTo(key) }
                .flatMapToSelectionForest { selection ->
                    selection.subselections.variableFreeProjectionSkeleton()
                }
        return ObjectSelection.of(
            key = key,
            possibleTypes = setOf(source.type),
            subselections =
                (exact.subselections + conservative)
                    .coalesceEquivalentSelections(),
        )
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    private fun propagateDemand(
        value: EngineResult?,
        demand: SelectionForest,
    ) {
        when (value) {
            null,
            Value.Error,
            is Value.Simple,
            -> Unit
            is EngineResult.Object ->
                runtime.orchestrator(value).addDemand(demand)
            is EngineResult.List ->
                value.forEachIndexed { _, cell ->
                    propagateDemand(
                        value = cell.getValue().get(),
                        demand = demand,
                    )
                }
        }
    }

    context(world: Assumptions)
    private fun ObjectSelection.mayContributeTo(
        targetKey: Value.GroundKey,
    ): Boolean {
        if (key.field != targetKey.field) return false
        val variables = key.arguments.usedVariables()
        if (
            variables.any { it is Value.Variable.Template } ||
            variables
                .filterIsInstance<Value.Variable.Stamped>()
                .any { !world.isBound(it) }
        ) {
            return true
        }
        return groundImmediately().groundKey() == targetKey
    }

    context(world: Assumptions)
    private fun ObjectSelection.groundImmediately(): ObjectSelection {
        return ObjectSelectionForest.of(source.type, listOf(this))
            .instantiateBindings()
            .single() as ObjectSelection
    }

    context(world: Assumptions)
    private suspend fun ObjectSelection.fetchGroundSelection(): ObjectSelection {
        val specialized = objectKey(source.type)
        return ObjectSelection.of(
            key =
                Value.GroundKey.of(
                    specialized.field,
                    specialized.arguments.fetchBindings(),
                ),
            possibleTypes = setOf(source.type),
            subselections = subselections,
        )
    }

    context(world: Assumptions, runtimeSupport: RuntimeSupport)
    private suspend fun EngineResult?.materialize(
        selections: SelectionForest,
        reader: List<PathComponent>,
    ): Value.Output? =
        when (this) {
            null -> null
            Value.Error -> Value.Error
            is Value.Simple -> this
            is EngineResult.Object ->
                runtime.orchestrator(this).materialize(selections, reader)
            is EngineResult.List -> {
                val materialized = mutableListOf<Value.Output?>()
                for (index in indices) {
                    materialized +=
                        get(index)
                            .getValue()
                            .await()
                            .materialize(selections, reader)
                }
                Value.OutputList.of(
                    typeExpr = typeExpr,
                    values = materialized,
                )
            }
        }
}

private class OpenSlot(
    val key: Value.GroundKey,
    val cell: EngineResult.Cell,
) {
    val promise: Promise<EngineResult?>
        get() = cell.getValue()

    fun complete(
        value: EngineResult?,
        accessAccepted: Value.Boolean,
    ) {
        promise.complete(value)
        cell.setAccessAccepted(accessAccepted)
    }
}

private data class OpenSelectionSignature(
    val key: Value.Key,
    val possibleTypes: Set<Schema.ObjectType>,
    val subselections: Set<OpenSelectionSignature>,
)

private data class OpenSelectionCoordinate(
    val key: Value.Key,
    val possibleTypes: Set<Schema.ObjectType>,
)

private fun Selection.signature(): OpenSelectionSignature =
    OpenSelectionSignature(
        key = key,
        possibleTypes = possibleTypes,
        subselections = subselections.signatures(),
    )

private fun SelectionForest.signatures(): Set<OpenSelectionSignature> {
    val signatures = linkedSetOf<OpenSelectionSignature>()
    forEach { selection ->
        signatures += selection.signature()
    }
    return signatures
}

private fun SelectionForest.coalesceEquivalentSelections(): SelectionForest {
    val childrenByCoordinate =
        linkedMapOf<OpenSelectionCoordinate, MutableList<SelectionForest>>()
    forEach { selection ->
        val coordinate =
            OpenSelectionCoordinate(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
            )
        childrenByCoordinate
            .getOrPut(coordinate, ::mutableListOf)
            .add(selection.subselections)
    }
    return childrenByCoordinate.entries
        .map { entry ->
            val coordinate = entry.key
            val children =
                entry.value
                    .concatenateSelectionForests()
                    .coalesceEquivalentSelections()
            Selection.of(
                key = coordinate.key,
                possibleTypes = coordinate.possibleTypes,
                subselections = children,
            )
        }.toSelectionForest()
}

context(world: Assumptions)
private fun Schema.ObjectType.projectionEnvelope(
    incoming: SelectionForest,
): ObjectSelectionForest {
    var demand = incoming
    val expandedFields = linkedSetOf<Schema.ObjectField>()
    while (true) {
        val applicable = demand.merge(this)
        val newlyActivated =
            applicable
                .byKey()
                .values
                .map { selection -> selection.key.field }
                .filter { field ->
                    field in world.resolverRegistry && expandedFields.add(field)
                }
        if (newlyActivated.isEmpty()) return applicable
        demand =
            demand +
                newlyActivated.flatMapToSelectionForest { field ->
                    world.resolverRegistry.resolver(field).objectFragment
                }
    }
}

private fun Selection.objectSelection(type: Schema.ObjectType): ObjectSelection =
    ObjectSelection.of(
        key = objectKey(type),
        possibleTypes = setOf(type),
        subselections = subselections,
    )

private fun SelectionForest.variableFreeProjectionSkeleton(): SelectionForest =
    flatMap { selection ->
        if (selection.key.arguments.usedVariables().isNotEmpty()) {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = selectionForestOf(),
                ),
            )
        } else {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections =
                        selection.subselections.variableFreeProjectionSkeleton(),
                ),
            )
        }
    }

private fun EngineResult.toProviderInput(): Value.Input =
    when (this) {
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.List -> toProviderInputList()
        is EngineResult.Object ->
            error("A FromObjectPath provider cannot terminate at an object")
    }

@Suppress("UNCHECKED_CAST")
private fun EngineResult.List.toProviderInputList(): Value.InputList {
    require(typeExpr.baseType is Schema.InputType) {
        "A FromObjectPath list must contain input-compatible simple values"
    }
    return Value.InputList.of(
        typeExpr = typeExpr as TypeExpr<Schema.InputType>,
        values =
            map { cell ->
                cell.getValue().get()?.toProviderInput()
            },
    )
}
