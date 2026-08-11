package semantics.resolver25

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
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
import model.containsErrorValue
import model.flatMapToSelectionForest
import model.groundKey
import model.merge
import model.mergeWithVariables
import model.selectionForestOf
import model.toSelectionForest
import model.usedVariables
import model.registry.VariableDefinition
import model.registry.successorDemand
import semantics.RuntimeSupport
import semantics.bindFromArguments
import semantics.correctresolution.argumentsContainErrorValue
import semantics.materialize

/**
 * Resolves selective demand once per exact resolver instance after strict per-field preparation.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    require(world.selectiveResolvers) {
        "Resolver25 requires selective resolvers"
    }
    return runBlocking {
        withTimeout(90_000) {
            context(RuntimeSupport.cycleChecking()) {
                val result: EngineResult.Object =
                    EngineResult.Object.of(
                        type = type,
                        values = emptyMap(),
                        mutable = true,
                    )
                coroutineScope {
                    val runtime = ResolverRuntime(this)
                    runtime.createOrchestrator(
                        path = emptyList(),
                        source = this@resolve,
                        target = result,
                        initialDemand = selections,
                    )
                }
                result
            }
        }
    }
}

private class ResolverRuntime(
    val scope: CoroutineScope,
) {
    private val orchestratedTargets: MutableSet<EngineResult.Object> =
        Collections.newSetFromMap(IdentityHashMap())

    // Creates and starts the sole orchestrator for one object-result instance. The returned latch
    // opens after every demanded promise on that instance has been installed.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    fun createOrchestrator(
        path: List<PathComponent>,
        source: Value.Object,
        target: EngineResult.Object,
        initialDemand: SelectionForest,
    ): Deferred<Unit> {
        check(orchestratedTargets.add(target)) {
            "Resolver25 received late demand for an existing OER at $path"
        }
        val orchestrator: ObjectResultOrchestrator =
            ObjectResultOrchestrator(
                runtime = this,
                plan = StrictPreparationPlan.forType(world, source.type),
                path = path,
                source = source,
                target = target,
            )
        orchestrator.addDemand(initialDemand)
        orchestrator.addDemand(
            source.type
                .closeStructuralDemand(initialDemand)
                .instanceIndependentDemand(),
        )
        orchestrator.start()
        return orchestrator.orchestrationReady
    }
}

// Closes demand under each activated resolver field's fixed object fragment. A resolver field is
// expanded once regardless of how many open or ground keys currently select it.
context(world: Assumptions)
private fun Schema.ObjectType.closeStructuralDemand(
    incoming: SelectionForest,
): ObjectSelectionForest {
    var demand = incoming
    val expandedFields = linkedSetOf<Schema.ObjectField>()
    while (true) {
        val merged: ObjectSelectionForest = demand.merge(this)
        val newlyActivatedFields: List<Schema.ObjectField> =
            merged
                .byKey()
                .values
                .filter { selection ->
                    !selection.key.arguments.containsErrorValue()
                }
                .map { selection -> selection.key.field }
                .filter { field ->
                    field in world.resolverRegistry && expandedFields.add(field)
                }
        if (newlyActivatedFields.isEmpty()) return merged
        demand =
            demand +
                newlyActivatedFields.flatMapToSelectionForest { field ->
                    world.resolverRegistry.resolver(field).objectFragment
                }
    }
}

// Retains the structurally fixed portion of demand. Variable-bearing resolver boundaries are
// activated by structural closure but become executable only for an exact resolver instance.
private fun SelectionForest.instanceIndependentDemand(): SelectionForest =
    flatMap { selection ->
        if (
            selection.key is Value.VariableKey ||
            selection.key.arguments.usedVariables().isNotEmpty()
        ) {
            selectionForestOf()
        } else {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = selection.subselections.instanceIndependentDemand(),
                ),
            )
        }
    }

/**
 * Resolves one object-result instance through per-field preparation latches.
 *
 * Synchronous structural closure contributes every activated resolver field's fixed demand before
 * orchestration starts. A field then waits only for instance-specific variable demand and marker
 * paths before grounding equal keys, preparing each resulting resolver instance exactly once, and
 * publishing the immutable demand consumed during launch.
 */
private class ObjectResultOrchestrator(
    private val runtime: ResolverRuntime,
    private val plan: StrictPreparationPlan.TypePlan,
    private val path: List<PathComponent>,
    private val source: Value.Object,
    private val target: EngineResult.Object,
) {
    val orchestrationReady: CompletableDeferred<Unit> = CompletableDeferred()

    private val fields: Map<Schema.ObjectField, FieldState> =
        source.type.fields.values.associateWith(::FieldState)

    // Starts preparation and launch coordination independently for every field. It also releases
    // the orchestration-ready latch after every field has installed its demanded promises.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    fun start() {
        fields.values.forEach { state ->
            runtime.scope.launch {
                prepareResolverInstances(state)
            }
            runtime.scope.launch {
                launchResolverInstances(state)
            }
        }
        runtime.scope.launch {
            fields.values.forEach { state ->
                state.promisesInstalled.await()
            }
            orchestrationReady.complete(Unit)
        }
    }

    // Adds applicable demand to the corresponding field states without erasing variable markers.
    fun addDemand(demand: SelectionForest) {
        demand.forEach { selection ->
            if (source.type in selection.possibleTypes) {
                val field: Schema.ObjectField =
                    source.type.fields.getValue(selection.key.field.fieldName)
                fields.getValue(field).add(selection)
            }
        }
    }

    // Waits for every source of this field's demand, seals the exact selections, and prepares each
    // resulting resolver instance. Publishing sealedDemand reports that all preparation is done.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun prepareResolverInstances(state: FieldState) {
        plan.demandContributors(state.field).forEach { contributor ->
            fields.getValue(contributor).sealedDemand.await()
        }
        plan.incomingPathVarProviders(state.field).forEach { provider ->
            fields.getValue(provider).promisesInstalled.await()
        }

        val mergedDemand:
            Pair<
                ObjectSelectionForest,
                Map<Value.Variable.Stamped, Value.GroundKey>,
            > =
            state
                .snapshot()
                .toSelectionForest()
                .mergeWithVariables(source.type)
        val selectionsByKey: Map<Value.GroundKey, ObjectSelection> =
            mergedDemand.first.byGroundKey()
        val variablesByKey: Map<Value.GroundKey, Set<Value.Variable.Stamped>> =
            mergedDemand.second.entries
                .groupBy(
                    keySelector = Map.Entry<Value.Variable.Stamped, Value.GroundKey>::value,
                    valueTransform = Map.Entry<Value.Variable.Stamped, Value.GroundKey>::key,
                ).mapValues { (_, variables) ->
                    variables.toSet()
                }

        selectionsByKey.keys.forEach { key ->
            prepareResolverInstance(key)
        }
        state.sealedDemand.complete(
            PreparedFieldDemand(
                selectionsByKey = selectionsByKey,
                variablesByKey = variablesByKey,
            ),
        )
    }

    /**
     * Prepares one exact resolver instance without launching its resolver.
     *
     * This operation:
     * - binds arg-variables;
     * - contributes instance-specific stamped object-fragment demand;
     * - declares path-variable bindings;
     * - contributes marker paths that will complete those bindings.
     */
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private fun prepareResolverInstance(key: Value.GroundKey) {
        if (
            key.arguments.argumentsContainErrorValue() ||
            key.field !in world.resolverRegistry
        ) {
            return
        }

        listOf(key).bindFromArguments(path)
        val resolver = world.resolverRegistry.resolver(key.field)
        val coordinate = path + key
        val definitions = resolver.stampedPathVarDefinitions(coordinate)
        definitions.forEach { definition ->
            world.declareBinding(definition.variable)
        }
        addDemand(
            resolver
                .stampedObjectFragment(coordinate)
                .instanceSpecificDemand(),
        )
    }

    // Eagerly installs promises so the orchestration-ready latch can be released, waits until
    // resolver-input promises can be looked up, then launches one coroutine per unresolved key.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun launchResolverInstances(state: FieldState) {
        val demand: PreparedFieldDemand = state.sealedDemand.await()
        val unresolved: List<Value.GroundKey> =
            demand.selectionsByKey.keys.filter { key ->
                !target.isValueSet(key)
            }
        unresolved.forEach { key ->
            target.createValuePromise(key)
            diagnosticInstrumentation.registerWriter(
                target = target,
                key = key,
                writer = path + key,
            )
        }
        state.promisesInstalled.complete(Unit)

        plan.resolverInputFields(state.field).forEach { inputField ->
            fields.getValue(inputField).promisesInstalled.await()
        }

        coroutineScope {
            unresolved.forEach { key ->
                launch {
                    resolveKey(
                        selection = demand.selectionsByKey.getValue(key),
                        valuePromise = target.getValue(key),
                        variablesDefinedByKey = demand.variablesByKey[key].orEmpty(),
                    )
                }
            }
        }
    }

    // Produces one exact field value from its materialized object fragment and requested output
    // demand. Child object results become orchestration-ready before this value is published.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun resolveKey(
        selection: ObjectSelection,
        valuePromise: Promise<EngineResult?>,
        variablesDefinedByKey: Set<Value.Variable.Stamped>,
    ) {
        val key = selection.groundKey()
        when {
            key.arguments.argumentsContainErrorValue() ->
                completeValueAndBindings(
                    valuePromise = valuePromise,
                    value = Value.Error,
                    variables = variablesDefinedByKey,
                )
            key.field.fieldName == "__typename" ->
                completeValueAndBindings(
                    valuePromise = valuePromise,
                    value = Value.String.of(source.type.typeName),
                    variables = variablesDefinedByKey,
                )
            else -> {
                val resolutionSelections: SelectionForest =
                    selection.subselections.successorDemand()
                val fieldValue: Value.Output? =
                    if (key.field in world.resolverRegistry) {
                        val resolver = world.resolverRegistry.resolver(key.field)
                        val coordinate = path + key
                        val input: Value.Object =
                            target.materialize(
                                selections = resolver.objectFragmentAt(coordinate),
                                reader = coordinate,
                            )
                        resolver(
                            input = input,
                            arguments = key.arguments,
                            selections = resolutionSelections,
                        )
                    } else {
                        error("Resolver25 cannot resolve passive key $key")
                    }
                val resolvedValue: ResolvedValue =
                    fieldValue.resolveValue(
                        path = path + key,
                        resolverDemand = resolutionSelections,
                    )

                val descendantsNeedingResolution: List<Deferred<Unit>> =
                    resolvedValue.objectsNeedingResolution.map { child ->
                        runtime.createOrchestrator(
                            path = child.path,
                            source = child.source,
                            target = child.target,
                            initialDemand = child.selections,
                        )
                    }
                descendantsNeedingResolution.awaitAll()
                completeValueAndBindings(
                    valuePromise = valuePromise,
                    value = resolvedValue.engineResult,
                    variables = variablesDefinedByKey,
                )
            }
        }
    }

    // Publishes the exact field value before releasing any path-variable consumers of that value.
    context(world: Assumptions)
    private fun completeValueAndBindings(
        valuePromise: Promise<EngineResult?>,
        value: EngineResult?,
        variables: Set<Value.Variable.Stamped>,
    ) {
        val providerInput: Value.Input? =
            if (variables.isEmpty()) null else value.toProviderInput()
        valuePromise.complete(value)
        variables.forEach { variable ->
            world.completeBinding(variable, providerInput)
        }
    }

    private class FieldState(
        val field: Schema.ObjectField,
    ) {
        private val selections: MutableList<Selection> = mutableListOf()

        // Carries the immutable demand and acts as the preparation latch: completion means demand
        // is sealed and every exact resolver instance for this field has been prepared.
        val sealedDemand: CompletableDeferred<PreparedFieldDemand> =
            CompletableDeferred()

        // Opens once every demanded value promise exists.
        val promisesInstalled: CompletableDeferred<Unit> = CompletableDeferred()

        // Records one applicable selection while this field's demand is still open.
        @Synchronized
        fun add(selection: Selection) {
            check(!sealedDemand.isCompleted) {
                "Demand arrived after ${field.containingType.typeName}/${field.fieldName} sealed"
            }
            selections += selection
        }

        // Returns a stable view of the selections accumulated before sealing.
        @Synchronized
        fun snapshot(): List<Selection> = selections.toList()
    }

    private class PreparedFieldDemand(
        val selectionsByKey: Map<Value.GroundKey, ObjectSelection>,
        val variablesByKey: Map<Value.GroundKey, Set<Value.Variable.Stamped>>,
    )
}

// Retains variable-bearing selections, their argument-independent ancestor paths, and provider
// markers. Fixed demand has already been contributed by structural closure.
private fun SelectionForest.instanceSpecificDemand(): SelectionForest =
    flatMap { selection ->
        if (
            selection.key is Value.VariableKey ||
            selection.key.arguments.usedVariables().isNotEmpty()
        ) {
            selectionForestOf(selection)
        } else {
            val instanceSpecificChildren: SelectionForest =
                selection.subselections.instanceSpecificDemand()
            if (instanceSpecificChildren.isEmpty()) {
                selectionForestOf()
            } else {
                selectionForestOf(
                    Selection.of(
                        key = selection.key,
                        possibleTypes = selection.possibleTypes,
                        subselections = instanceSpecificChildren,
                    ),
                )
            }
        }
    }

/**
 * Converts one resolver output to its passive engine-result shape and identifies object results
 * that still contain active resolver demand. Path-variable markers are consumed at the object
 * instance where their terminal field becomes an exact ground key.
 */
context(world: Assumptions)
private suspend fun Value.Output?.resolveValue(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
): ResolvedValue =
    when (this) {
        null -> ResolvedValue(null, emptyList())
        Value.Error -> ResolvedValue(Value.Error, emptyList())
        is Value.Simple -> ResolvedValue(this, emptyList())
        is Value.Object -> resolveObjectValue(path, resolverDemand)
        is Value.OutputList -> {
            val resolvedElements: List<ResolvedValue> =
                values.mapIndexed { index, value ->
                    value.resolveValue(
                        path = path + Value.ListIndex.of(index),
                        resolverDemand = resolverDemand,
                    )
                }
            ResolvedValue(
                engineResult =
                    EngineResult.List.of(
                        typeExpr = typeExpr,
                        values = resolvedElements.map(ResolvedValue::engineResult),
                    ),
                objectsNeedingResolution =
                    resolvedElements.flatMap(ResolvedValue::objectsNeedingResolution),
            )
        }
    }

// Selects passive output fields, completes path-variable bindings at terminal marker keys, and
// retains this object instance when any selected key crosses a resolver boundary.
context(world: Assumptions)
private suspend fun Value.Object.resolveObjectValue(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
): ResolvedValue {
    val mergedDemand:
        Pair<
            ObjectSelectionForest,
            Map<Value.Variable.Stamped, Value.GroundKey>,
        > =
        resolverDemand.mergeWithVariables(type)
    val resolverDemandByKey: Map<Value.GroundKey, ObjectSelection> =
        mergedDemand.first.byGroundKey()
    val variablesByKey: Map<Value.GroundKey, Set<Value.Variable.Stamped>> =
        mergedDemand.second.entries
            .groupBy(
                keySelector = Map.Entry<Value.Variable.Stamped, Value.GroundKey>::value,
                valueTransform = Map.Entry<Value.Variable.Stamped, Value.GroundKey>::key,
            ).mapValues { (_, variables) ->
                variables.toSet()
            }
    val unselectedKeys: Set<Value.GroundKey> = fieldValues.keys - resolverDemandByKey.keys
    require(unselectedKeys.isEmpty()) {
        "Selective resolver output ${type.typeName} contains unselected fields: " +
            unselectedKeys.joinToString { key -> key.field.fieldName }
    }

    val selectedKeys: Set<Value.GroundKey> =
        resolverDemandByKey.keys
            .filterTo(linkedSetOf()) { key ->
                key.field !in world.resolverRegistry
            }
    val resolvedFields: List<ResolvedField> =
        selectedKeys.map { key ->
            val resolvedValue: ResolvedValue =
                if (key.field.fieldName == "__typename") {
                    ResolvedValue(Value.String.of(type.typeName), emptyList())
                } else {
                    fieldValues
                        .getValue(key)
                        .resolveValue(
                            path = path + key,
                            resolverDemand =
                                resolverDemandByKey
                                    .getValue(key)
                                    .subselections,
                        )
                }
            completeBindings(
                variables = variablesByKey[key].orEmpty(),
                value = resolvedValue.engineResult,
            )
            ResolvedField(key, resolvedValue)
        }
    val engineResult: EngineResult.Object =
        EngineResult.Object.of(
            type = type,
            values =
                resolvedFields.associate { resolvedField ->
                    resolvedField.key to resolvedField.value.engineResult
                },
            mutable = true,
        )
    val localResolution: List<ObjectResolution> =
        if (resolverDemandByKey.keys.any { key -> key.field in world.resolverRegistry }) {
            listOf(
                ObjectResolution(
                    path = path,
                    source = this,
                    selections = resolverDemand,
                    target = engineResult,
                ),
            )
        } else {
            emptyList()
        }
    return ResolvedValue(
        engineResult = engineResult,
        objectsNeedingResolution =
            localResolution +
                resolvedFields.flatMap { resolvedField ->
                    resolvedField.value.objectsNeedingResolution
                },
    )
}

// Completes every path-variable whose provider marker resolved to this exact field key.
context(world: Assumptions)
private fun completeBindings(
    variables: Set<Value.Variable.Stamped>,
    value: EngineResult?,
) {
    if (variables.isEmpty()) return
    val providerInput: Value.Input? = value.toProviderInput()
    variables.forEach { variable ->
        world.completeBinding(variable, providerInput)
    }
}

private class ResolvedValue(
    val engineResult: EngineResult?,
    val objectsNeedingResolution: List<ObjectResolution>,
)

private class ObjectResolution(
    val path: List<PathComponent>,
    val source: Value.Object,
    val selections: SelectionForest,
    val target: EngineResult.Object,
)

private class ResolvedField(
    val key: Value.GroundKey,
    val value: ResolvedValue,
)

private object StrictPreparationPlan {
    class TypePlan(
        private val demandContributorsByField:
            Map<Schema.ObjectField, Set<Schema.ObjectField>>,
        private val incomingPathVarProvidersByField:
            Map<Schema.ObjectField, Set<Schema.ObjectField>>,
        private val resolverInputFieldsByField:
            Map<Schema.ObjectField, Set<Schema.ObjectField>>,
    ) {
        // Returns fields whose preparation can contribute instance-specific demand to this field.
        fun demandContributors(field: Schema.ObjectField): Set<Schema.ObjectField> =
            demandContributorsByField[field].orEmpty()

        // Returns path-variable provider fields whose promises must exist before this field prepares.
        fun incomingPathVarProviders(field: Schema.ObjectField): Set<Schema.ObjectField> =
            incomingPathVarProvidersByField[field].orEmpty()

        // Returns object-fragment input fields whose promises must exist before this field launches.
        fun resolverInputFields(field: Schema.ObjectField): Set<Schema.ObjectField> =
            resolverInputFieldsByField[field].orEmpty()
    }

    enum class Phase {
        PREPARE,
        LAUNCH,
    }

    data class FieldStep(
        val field: Schema.ObjectField,
        val phase: Phase,
    )

    // Builds the strict two-phase field plan for one concrete object type.
    fun forType(
        assumptions: Assumptions,
        type: Schema.ObjectType,
    ): TypePlan = buildTypePlan(assumptions, type)

    // Constructs and validates the prepare/launch dependency graph, then projects it into the three
    // dependency sets consumed by object-result orchestration.
    private fun buildTypePlan(
        world: Assumptions,
        type: Schema.ObjectType,
    ): TypePlan {
        val objectFields = type.fields.values
        val dependenciesByStep: MutableMap<FieldStep, MutableSet<FieldStep>> =
            objectFields
                .flatMap { field ->
                    Phase.entries.map { phase -> FieldStep(field, phase) }
                }.associateWithTo(linkedMapOf()) {
                    linkedSetOf<FieldStep>()
                }

        // Records that one field step must complete before another can proceed.
        fun edge(
            before: FieldStep,
            after: FieldStep,
        ) {
            dependenciesByStep.getValue(after).add(before)
        }

        objectFields.forEach { field ->
            edge(
                FieldStep(field, Phase.PREPARE),
                FieldStep(field, Phase.LAUNCH),
            )
            if (field !in world.resolverRegistry) return@forEach

            val resolver = world.resolverRegistry.resolver(field)
            resolver.objectFragment.merge(type).byKey().values.forEach { selection ->
                val required = selection.key.field
                if (selection.usedVariables().isNotEmpty()) {
                    edge(
                        FieldStep(field, Phase.PREPARE),
                        FieldStep(required, Phase.PREPARE),
                    )
                }
                edge(
                    FieldStep(required, Phase.LAUNCH),
                    FieldStep(field, Phase.LAUNCH),
                )
            }
            resolver.variables.forEach { (variable, definition) ->
                if (definition !is VariableDefinition.FromObjectField) return@forEach
                require(
                    definition.path.all { key ->
                        key.field.arguments.fields.isEmpty()
                    },
                ) {
                    "Resolver25 path-variable provider paths cannot cross fields with arguments"
                }
                val provider: Schema.ObjectField =
                    type.fields.getValue(
                        definition.path.first().field.fieldName,
                    )
                edge(
                    FieldStep(field, Phase.PREPARE),
                    FieldStep(provider, Phase.PREPARE),
                )
                pathVarUseFields(resolver.objectFragment, variable).forEach { useField ->
                    edge(
                        FieldStep(provider, Phase.LAUNCH),
                        FieldStep(useField, Phase.PREPARE),
                    )
                }
            }
        }

        requireAcyclic(type, dependenciesByStep)
        return TypePlan(
            demandContributorsByField =
                objectFields.associateWith { field ->
                    dependenciesByStep
                        .getValue(FieldStep(field, Phase.PREPARE))
                        .filter { step -> step.phase == Phase.PREPARE }
                        .mapTo(linkedSetOf(), FieldStep::field)
                },
            incomingPathVarProvidersByField =
                objectFields.associateWith { field ->
                    dependenciesByStep
                        .getValue(FieldStep(field, Phase.PREPARE))
                        .filter { step -> step.phase == Phase.LAUNCH }
                        .mapTo(linkedSetOf(), FieldStep::field)
                },
            resolverInputFieldsByField =
                objectFields.associateWith { field ->
                    dependenciesByStep
                        .getValue(FieldStep(field, Phase.LAUNCH))
                        .filter { step -> step.phase == Phase.LAUNCH }
                        .mapTo(linkedSetOf(), FieldStep::field)
                },
        )
    }

    // Finds top-level object-fragment branches whose key or descendants consume this path-variable.
    private fun pathVarUseFields(
        fragment: SelectionForest,
        variable: Value.Variable.Template,
    ): Set<Schema.ObjectField> {
        val uses = linkedSetOf<Schema.ObjectField>()
        fragment.merge(variable.field.containingType).byKey().values.forEach { selection ->
            if (variable in selection.usedVariables()) {
                uses += selection.key.field
            }
        }
        return uses
    }

    // Rejects a type plan whose combined preparation and resolver-execution dependencies contain a
    // cycle.
    private fun requireAcyclic(
        type: Schema.ObjectType,
        dependenciesByStep: Map<FieldStep, Set<FieldStep>>,
    ) {
        val outgoingByStep: MutableMap<FieldStep, MutableSet<FieldStep>> =
            dependenciesByStep.keys.associateWithTo(linkedMapOf()) {
                linkedSetOf<FieldStep>()
            }
        dependenciesByStep.forEach { (step, requiredSteps) ->
            requiredSteps.forEach { requiredStep ->
                outgoingByStep.getValue(requiredStep).add(step)
            }
        }
        val visited = linkedSetOf<FieldStep>()
        val active = linkedSetOf<FieldStep>()
        val path = mutableListOf<FieldStep>()

        // Performs one depth-first cycle search while retaining the active path for diagnostics.
        fun visit(step: FieldStep): List<FieldStep>? {
            if (step in active) {
                val start = path.indexOf(step)
                return path.subList(start, path.size).toList() + step
            }
            if (!visited.add(step)) return null
            active += step
            path += step
            val cycle = outgoingByStep.getValue(step).firstNotNullOfOrNull(::visit)
            path.removeAt(path.lastIndex)
            active -= step
            return cycle
        }

        val cycle = outgoingByStep.keys.firstNotNullOfOrNull(::visit) ?: return
        throw IllegalArgumentException(
            "Resolver25 one-shot phase order on ${type.typeName} contains a cycle: " +
                cycle.joinToString(" -> ") { step ->
                    "${step.phase.name.lowercase()}(${step.field.fieldName})"
                },
        )
    }
}

// Converts a terminal provider result into the corresponding ground path-variable input.
private fun EngineResult?.toProviderInput(): Value.Input? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.List -> toProviderInputList()
        is EngineResult.Object ->
            error("A path-variable provider cannot terminate at an object")
    }

@Suppress("UNCHECKED_CAST")
private fun EngineResult.List.toProviderInputList(): Value.InputList {
    require(typeExpr.baseType is Schema.InputType) {
        "A path-variable provider list must contain input-compatible simple values"
    }
    return Value.InputList.of(
        typeExpr = typeExpr as TypeExpr<Schema.InputType>,
        values = map { value -> value?.toProviderInput() },
    )
}
