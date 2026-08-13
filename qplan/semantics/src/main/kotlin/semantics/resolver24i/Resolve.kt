package semantics.resolver24i

import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import model.applicableGroundSelections
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
import model.registry.VariableDefinition
import model.registry.successorGroundBoundaryDemand
import semantics.RuntimeSupport

/**
 * Presentation-oriented resolver for selective object-fragment resolution with both argument- and
 * object-path-bound variables. The public wrapper, coroutine construction, binding lifecycle,
 * demand handling, and passive result construction are specialized into this one file.
 *
 * Model carriers, binding/value [Promise] storage, [RuntimeSupport] cycle checking, and registry
 * successor-demand derivation remain shared primitives rather than being copied here.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    require(world.selectiveResolvers) {
        "Resolver24i requires selective resolvers"
    }
    return runBlocking {
        withTimeout(90_000) {
            val result =
                EngineResult.Object.of(
                    type = type,
                    values = emptyMap(),
                    mutable = true,
                )
            context(RuntimeSupport.cycleChecking()) {
                val runtime =
                    coroutineScope {
                        val runtime = ResolutionRuntime(scope = this)
                        runtime.createOrchestrator(
                            path = emptyList(),
                            source = this@resolve,
                            target = result,
                            initialDemand = selections,
                        )
                        runtime
                    }
                result.selectOutput(runtime)
            }
        }
    }
}

/** One structured root owns every OER orchestrator and every coroutine it launches. */
private class ResolutionRuntime(
    val scope: CoroutineScope,
) {
    private val orchestrators =
        IdentityHashMap<EngineResult.Object, ObjectOrchestrator>()

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    fun createOrchestrator(
        path: List<PathComponent>,
        source: Value.Object,
        target: EngineResult.Object,
        initialDemand: SelectionForest,
    ): ObjectOrchestrator {
        val existing = orchestrators[target]
        if (existing != null) {
            existing.requireOccurrence(path, source)
            existing.addDemand(initialDemand)
            return existing
        }
        val orchestrator =
            ObjectOrchestrator(
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

    fun orchestrator(target: EngineResult.Object): ObjectOrchestrator =
        checkNotNull(orchestrators[target]) {
            "No coroutine orchestrator registered for ${target.type.typeName} OER"
        }
}

/**
 * The sole consumer of variables used by keys in one OER.
 *
 * Every open selection eventually submits one ground key. The exact-key map then either creates
 * one slot or contributes more demand to its existing slot.
 */
private class ObjectOrchestrator(
    private val runtime: ResolutionRuntime,
    val path: List<PathComponent>,
    private val source: Value.Object,
    private val target: EngineResult.Object,
    private val initialDemand: SelectionForest,
) {
    private val exactSelections =
        linkedMapOf<Value.GroundKey, ObjectSelection>()
    private val slots =
        linkedMapOf<Value.GroundKey, Slot>()
    private val expanded =
        linkedSetOf<List<PathComponent>>()
    private val acceptedDemand =
        linkedSetOf<OpenSelectionSignature>()
    private lateinit var projectionEnvelope: ObjectSelectionForest

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
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

    fun outputDemand(): SelectionForest =
        exactSelections.values.toSelectionForest()

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    fun addDemand(demand: SelectionForest) {
        demand.merge(source.type).forEach { selection ->
            selection as ObjectSelection
            if (!acceptedDemand.add(selection.signature())) {
                return@forEach
            }
            check(
                selection.key.arguments.usedVariables().none {
                    it is Value.Variable.Template
                },
            ) {
                "Coroutine demand contains an unstamped variable: ${selection.key}"
            }
            if (selection.key.stampedVariables().all(world::isBound)) {
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

    /**
     * Grounds and installs the selected keys before reading them, so materialization cannot race
     * key activation after a binding completes.
     */
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    suspend fun materialize(
        selections: SelectionForest,
        reader: List<PathComponent>,
    ): Value.Object {
        val fields = linkedMapOf<Value.GroundKey, Value.Output?>()
        groundSelections(selections).byGroundKey().forEach { (key, selection) ->
            val slot =
                accept(
                    selection = selection,
                    propagateExisting = false,
                )
            diagnosticInstrumentation.cycleCheck(reader, target, key)
            fields[key] =
                slot.promise.await().materialize(
                    selections = selection.subselections,
                    reader = reader,
                )
        }
        return Value.Object.of(source.type, fields)
    }

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
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

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private fun accept(
        selection: ObjectSelection,
        propagateExisting: Boolean,
    ): Slot {
        val key = selection.groundKey()
        val previous = exactSelections[key]
        exactSelections[key] =
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

        val promise =
            if (target.isValueSet(key)) {
                target.getValue(key)
            } else {
                target.createValuePromise(key)
                diagnosticInstrumentation.registerWriter(
                    target = target,
                    key = key,
                    writer = path + key,
                )
                target.getValue(key)
            }
        val slot = Slot(key, promise)
        slots[key] = slot

        if (!promise.isCompleted) {
            expandResolverOccurrence(key)
            runtime.scope.launch(start = CoroutineStart.DEFAULT) {
                resolveSlot(slot)
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

    /**
     * The exact occurrence owns its variable definitions. It declares every provider promise
     * before launching one reader coroutine per definition.
     */
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private fun expandResolverOccurrence(key: Value.GroundKey) {
        if (
            key.arguments.containsErrorValue() ||
            key.field !in world.resolverRegistry
        ) {
            return
        }
        val coordinate = path + key
        check(expanded.add(coordinate)) {
            "Resolver occurrence expanded more than once: $coordinate"
        }

        bindFromArguments(key)
        val resolver = world.resolverRegistry.resolver(key.field)
        val definitions = resolver.stampedPathVarDefinitions(coordinate)
        definitions.forEach { definition ->
            world.declareBinding(definition.variable)
        }
        addDemand(resolver.stampVars(coordinate))
        definitions.forEach { definition ->
            runtime.scope.launch(start = CoroutineStart.DEFAULT) {
                world.completeBinding(
                    definition.variable,
                    readProvider(definition, coordinate),
                )
            }
        }
    }

    context(world: Assumptions)
    private fun bindFromArguments(key: Value.GroundKey) {
        val resolver = world.resolverRegistry.resolver(key.field)
        resolver.variables.forEach { (variable, definition) ->
            if (definition is VariableDefinition.FromArgument) {
                val stamped = variable.stamp(path + key)
                world.declareBinding(stamped)
                world.completeBinding(
                    stamped,
                    key.arguments.fieldValues.getValue(
                        definition.argument.argumentName,
                    ),
                )
            }
        }
    }

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
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

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun await(
        openKey: Value.Key,
        reader: List<PathComponent>,
    ): EngineResult? {
        val selection =
            Selection.of(
                key = openKey,
                possibleTypes = setOf(source.type),
                subselections = selectionForestOf(),
            ).asObjectSelection(source.type)
                .fetchGroundSelection()
        val key = selection.groundKey()
        val slot =
            accept(
                selection = selection,
                propagateExisting = false,
            )
        diagnosticInstrumentation.cycleCheck(reader, target, key)
        return slot.promise.await()
    }

    /** Resolver24i always retains complete internal output for late object-path reads. */
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun resolveSlot(slot: Slot) {
        val key = slot.key
        when {
            key.arguments.containsErrorValue() ->
                slot.promise.complete(Value.Error)
            key.field.fieldName == "__typename" ->
                slot.promise.complete(Value.String.of(source.type.typeName))
            else -> {
                val resolver = world.resolverRegistry.resolver(key.field)
                val selection = selectionForLaunch(key)
                val resolverDemand =
                    selection.subselections.successorGroundBoundaryDemand()
                val coordinate = path + key
                val input =
                    materialize(
                        selections = resolver.stampVars(coordinate),
                        reader = coordinate,
                    )
                val fieldValue =
                    resolver.completeOutput(
                        input = input,
                        arguments = key.arguments,
                        selections = resolverDemand,
                    )
                val resolvedValue =
                    fieldValue.resolveOutput(
                        path = coordinate,
                        resolverDemand = resolverDemand,
                    )

                resolvedValue.objectOccurrences.forEach { occurrence ->
                    runtime.createOrchestrator(
                        path = occurrence.path,
                        source = occurrence.source,
                        target = occurrence.target,
                        initialDemand = occurrence.selections,
                    )
                }
                slot.promise.complete(resolvedValue.engineResult)
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

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
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
                value.forEachIndexed { _, element ->
                    propagateDemand(element, demand)
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
    private fun ObjectSelection.groundImmediately(): ObjectSelection =
        ObjectSelectionForest.of(source.type, listOf(this))
            .instantiateBindings()
            .single() as ObjectSelection

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

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
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
                    materialized += get(index).materialize(selections, reader)
                }
                Value.OutputList.of(typeExpr, materialized)
            }
        }
}

private class Slot(
    val key: Value.GroundKey,
    val promise: Promise<EngineResult?>,
)

/* Passive result construction, specialized from ResolveValue.kt for selective Resolver24i. */

private class ResolvedValue(
    val engineResult: EngineResult?,
    val objectOccurrences: List<ObjectOccurrence>,
)

private class ObjectOccurrence(
    val path: List<PathComponent>,
    val source: Value.Object,
    val selections: SelectionForest,
    val target: EngineResult.Object,
)

context(world: Assumptions)
private fun EngineResult.Object.selectOutput(
    runtime: ResolutionRuntime,
): EngineResult.Object {
    val values =
        runtime
            .orchestrator(this)
            .outputDemand()
            .applicableGroundSelections(type)
            .byGroundKey()
            .mapValues { (key, _) ->
                getValue(key).get().selectOutput(runtime)
            }
    return EngineResult.Object.of(type, values)
}

context(world: Assumptions)
private fun EngineResult?.selectOutput(
    runtime: ResolutionRuntime,
): EngineResult? =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> this
        is EngineResult.Object -> selectOutput(runtime)
        is EngineResult.List ->
            EngineResult.List.of(
                typeExpr = typeExpr,
                values = map { value -> value.selectOutput(runtime) },
            )
    }

context(world: Assumptions)
private fun Value.Output?.resolveOutput(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
): ResolvedValue =
    when (this) {
        null -> ResolvedValue(null, emptyList())
        Value.Error -> ResolvedValue(Value.Error, emptyList())
        is Value.Simple -> ResolvedValue(this, emptyList())
        is Value.Object ->
            resolveObjectOutput(
                resolverDemand = resolverDemand,
                path = path,
            )
        is Value.OutputList -> {
            val values = mutableListOf<EngineResult?>()
            val occurrences = mutableListOf<ObjectOccurrence>()
            this.values.forEachIndexed { index, value ->
                val resolved =
                    value.resolveOutput(
                        path = path + Value.ListIndex.of(index),
                        resolverDemand = resolverDemand,
                    )
                values += resolved.engineResult
                occurrences += resolved.objectOccurrences
            }
            ResolvedValue(
                engineResult = EngineResult.List.of(typeExpr, values),
                objectOccurrences = occurrences,
            )
        }
    }

context(world: Assumptions)
private fun Value.Object.resolveObjectOutput(
    resolverDemand: SelectionForest,
    path: List<PathComponent>,
): ResolvedValue {
    val demand = resolverDemand.applicableGroundSelections(type)
    val demandByKey = demand.byGroundKey()
    val selectedKeys =
        fieldValues.keys.filter { key -> !world.behavioral(key.field) }.toSet() +
            demandByKey.keys.filter { key -> key.field.fieldName == "__typename" }
    val values = linkedMapOf<Value.GroundKey, EngineResult?>()
    val occurrences = mutableListOf<ObjectOccurrence>()
    selectedKeys.forEach { key ->
        if (key.field.fieldName == "__typename") {
            values[key] = Value.String.of(type.typeName)
        } else {
            val resolved =
                fieldValues
                    .getValue(key)
                    .resolveOutput(
                        path = path + key,
                        resolverDemand =
                            demandByKey[key]
                                ?.subselections
                                ?: selectionForestOf(),
                    )
            values[key] = resolved.engineResult
            occurrences += resolved.objectOccurrences
        }
    }

    val target = EngineResult.Object.of(type, values, mutable = true)
    return ResolvedValue(
        engineResult = target,
        objectOccurrences =
            listOf(
                ObjectOccurrence(
                    path = path,
                    source = this,
                    selections = resolverDemand,
                    target = target,
                ),
            ) + occurrences,
    )
}

/* Demand normalization and conservative sealing. */

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

private fun Selection.asObjectSelection(type: Schema.ObjectType): ObjectSelection =
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

/* Provider-value conversion. */

private fun Value.Arguments.containsErrorValue(): Boolean =
    fieldValues.values.any { value -> value.containsErrorValue() }

private fun Value.Input?.containsErrorValue(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.InputList -> values.any { value -> value.containsErrorValue() }
        is Value.InputObject ->
            fieldValues.values.any { value -> value.containsErrorValue() }
        else -> false
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
            map { value ->
                value?.toProviderInput()
            },
    )
}
