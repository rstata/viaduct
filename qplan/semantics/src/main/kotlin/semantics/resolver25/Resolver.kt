package semantics.resolver25

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.Promise
import model.Schema
import model.Selection
import model.SelectionForest
import model.SimpleEngineResult
import model.TypeExpr
import model.Value
import model.containsErrorValue
import model.fetchBindings
import model.flatMapToSelectionForest
import model.groundKey
import model.merge
import model.mergeWithVariables
import model.objectKey
import model.selectionForestOf
import model.toEngineResult
import model.toValue
import model.registry.StampedObjectPathDefinition
import model.registry.fetchSuccessorDemandDeferringTemplates
import semantics.RuntimeSupport
import semantics.bindFromArguments
import semantics.correctresolution.argumentsContainErrorValue
import semantics.materialize

/**
 * Resolves selective demand once per grounded resolver instance.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolveWithLifecycleInstrumentation(selections)

context(world: Assumptions)
internal fun resolveObserved(
    selections: SelectionForest,
    eventObserver: Resolver25LifecycleEventObserver,
): ObjectEngineResult =
    resolveWithLifecycleInstrumentation(selections, eventObserver)

context(world: Assumptions)
private fun resolveWithLifecycleInstrumentation(
    selections: SelectionForest,
    eventObserver: Resolver25LifecycleEventObserver? = null,
): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver25 requires selective resolvers"
    }
    val source = world.resolverRegistry.resolveRootQuery()
    return runBlocking {
        withTimeout(90_000) {
            context(RuntimeSupport.cycleChecking()) {
                val result: ObjectEngineResult =
                    source.type.newObjectResult()
                coroutineScope {
                    val runtime =
                        ResolverRuntime(
                            scope = this,
                            instrumentation =
                                Resolver25LifecycleInstrumentation(eventObserver),
                        )
                    runtime.createOrchestrator(
                        path = emptyList(),
                        source = source,
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
    val instrumentation: Resolver25LifecycleInstrumentation,
) {
    private val orchestratorsByTarget:
        MutableMap<ObjectEngineResult, ObjectResultOrchestrator> =
        Collections.synchronizedMap(IdentityHashMap())

    // Creates the sole orchestrator for one object-result instance or contributes late actual
    // demand to the existing orchestrator. The returned latch covers this contribution.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    fun createOrchestrator(
        path: List<PathComponent>,
        source: Value.Object,
        target: ObjectEngineResult,
        initialDemand: SelectionForest,
        potentialDemand: SelectionForest = initialDemand,
    ): Deferred<Unit> {
        synchronized(orchestratorsByTarget) {
            orchestratorsByTarget[target]?.let { orchestrator ->
                return orchestrator.addDemand(initialDemand).asLatch()
            }
            val orchestrator: ObjectResultOrchestrator =
                ObjectResultOrchestrator(
                    runtime = this,
                    path = path,
                    source = source,
                    target = target,
                )
            check(orchestratorsByTarget.put(target, orchestrator) == null) {
                "Resolver25 concurrently created two orchestrators at $path"
            }
            instrumentation.orchestratorCreated(path, source.type)
            orchestrator.addPotentialDemand(
                source.type.closeStructuralDemand(potentialDemand),
            )
            orchestrator.addDemand(initialDemand)
            orchestrator.start()
            return orchestrator.orchestrationReady
        }
    }

    private fun List<Deferred<Unit>>.asLatch(): Deferred<Unit> {
        if (isEmpty()) return CompletableDeferred(Unit)
        val complete = CompletableDeferred<Unit>()
        scope.launch {
            awaitAll()
            complete.complete(Unit)
        }
        return complete
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

private fun Schema.ObjectType.newObjectResult(): ObjectEngineResult =
    ObjectEngineResult.of(
        type = this,
        mutable = true,
    )

/**
 * Resolves one object-result instance through per-grounded-key activation.
 *
 * Structural closure supplies each field's conservative output-demand envelope. Runtime activation
 * grounds individual selections, merges equal keys, expands FromArgument demand locally, and
 * launches each resolver instance at most once. Path-variable selections remain pending activation
 * work until their readers complete the required bindings.
 */
private class ObjectResultOrchestrator(
    private val runtime: ResolverRuntime,
    private val path: List<PathComponent>,
    private val source: Value.Object,
    private val target: ObjectEngineResult,
) {
    val orchestrationReady: CompletableDeferred<Unit> = CompletableDeferred()

    private val fields: Map<Schema.ObjectField, FieldState> =
        source.type.fields.values.associateWith(::FieldState)

    private val activationLock = Any()
    private var pendingActivations: Int = 0
    private var started: Boolean = false
    private val activationsComplete: CompletableDeferred<Unit> = CompletableDeferred()

    // Records every structurally possible output subselection before grounded-key activation.
    fun addPotentialDemand(demand: SelectionForest) {
        demand.forEach { selection ->
            if (source.type in selection.possibleTypes) {
                val field =
                    source.type.fields.getValue(selection.key.field.fieldName)
                fields.getValue(field).addPotentialSelection(selection)
            }
        }
    }

    // Starts completion coordination after all initial activation work has been submitted.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    fun start() {
        val completeImmediately =
            synchronized(activationLock) {
                check(!started)
                started = true
                pendingActivations == 0
            }
        if (completeImmediately) {
            activationsComplete.complete(Unit)
        }
        runtime.scope.launch {
            activationsComplete.await()
            runtime.instrumentation.orchestratorReady(path)
            orchestrationReady.complete(Unit)
        }
    }

    // Submits applicable selections as independently groundable activation work.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    fun addDemand(
        demand: SelectionForest,
        consumerCoordinate: List<PathComponent>? = null,
    ): List<Deferred<Unit>> {
        val activations = mutableListOf<Deferred<Unit>>()
        demand.forEach { selection ->
            if (source.type in selection.possibleTypes) {
                activations += submitActivation(selection, consumerCoordinate)
            }
        }
        return activations
    }

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private fun submitActivation(
        selection: Selection,
        consumerCoordinate: List<PathComponent>?,
    ): Deferred<Unit> {
        synchronized(activationLock) {
            pendingActivations += 1
        }
        val contributionId =
            runtime.instrumentation.demandSubmitted(
                path,
                consumerCoordinate,
                selection,
            )
        val activationComplete = CompletableDeferred<Unit>()
        // Merge immediately groundable demand before an existing key can seal; bindings still yield.
        runtime.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val coordinates = activateSelection(selection, contributionId)
                coordinates.forEach { coordinate ->
                    runtime.instrumentation.contributionInstalled(
                        contributionId,
                        coordinate,
                    )
                }
                activationComplete.complete(Unit)
            } finally {
                finishActivation()
            }
        }
        return activationComplete
    }

    private fun finishActivation() {
        val complete =
            synchronized(activationLock) {
                pendingActivations -= 1
                check(pendingActivations >= 0)
                started && pendingActivations == 0
            }
        if (complete) {
            activationsComplete.complete(Unit)
        }
    }

    // Grounds one occurrence, interns it by grounded key, and waits for its promise/fringe to exist.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun activateSelection(
        selection: Selection,
        contributionId: DemandContributionId,
    ): List<List<PathComponent>> {
        val groundedSelections: ObjectSelectionForest =
            selectionForestOf(selection)
                .mergeWithVariables(target)
                .first
        return groundedSelections.byGroundKey().values.map { groundedSelection ->
            val coordinate = path + groundedSelection.groundKey()
            runtime.instrumentation.demandGrounded(contributionId, coordinate)
            activateGroundedSelection(groundedSelection, contributionId).await()
            coordinate
        }
    }

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private fun activateGroundedSelection(
        groundedSelection: ObjectSelection,
        contributionId: DemandContributionId,
    ): Deferred<Unit> {
        val groundedKey = groundedSelection.groundKey()
        val coordinate = path + groundedKey
        val fieldState = fields.getValue(groundedKey.field)
        val activation =
            fieldState.activate(
                groundedKey = groundedKey,
                groundedSelection = groundedSelection,
                concreteType = source.type,
            )
        if (!activation.created) {
            runtime.instrumentation.groundedDemandMerged(
                contributionId = contributionId,
                coordinate = coordinate,
                beforeLaunch = activation.mergedBeforeLaunch,
            )
            if (groundedKey in source.fieldValues.keys) {
                return launchMergedPassiveDemand(
                    keyState = activation.keyState,
                    demand = groundedSelection.subselections,
                )
            }
            if (activation.mergedBeforeLaunch) {
                return activation.keyState.fringeInstalled
            }
            return launchLateOutputDemand(
                keyState = activation.keyState,
                demand = groundedSelection.subselections,
            )
        }

        val keyState = activation.keyState
        val existing = target.isCellSet(groundedKey)
        val keyKind =
            when {
                existing -> Resolver25KeyKind.PREEXISTING
                groundedKey.arguments.argumentsContainErrorValue() ->
                    Resolver25KeyKind.ERROR
                groundedKey in source.fieldValues.keys ->
                    Resolver25KeyKind.PASSIVE
                else -> Resolver25KeyKind.FIELD_RESOLVER
            }
        runtime.instrumentation.groundedKeyInterned(
            contributionId,
            coordinate,
            keyKind,
        )
        if (!existing) {
            val cell = target.reserveCell(groundedKey)
            cell.createValuePromise()
            diagnosticInstrumentation.registerWriter(
                cell = cell,
                writer = path + groundedKey,
            )
            runtime.instrumentation.valuePromiseInstalled(coordinate)
        }
        keyState.promiseInstalled.complete(Unit)

        when {
            existing -> {
                val nestedFringe: List<Deferred<Unit>> =
                    source.fieldValues
                        .getValue(groundedKey)
                        .launchNestedFringe(
                            result = target.getCell(groundedKey).getValue().get(),
                            path = coordinate,
                            demand = keyState.demandSnapshot().subselections,
                            potentialDemand = keyState.potentialDemand,
                        )
                runtime.scope.launch {
                    nestedFringe.awaitAll()
                    runtime.instrumentation.keyActivationReady(coordinate)
                    keyState.fringeInstalled.complete(Unit)
                }
            }

            groundedKey.field in world.resolverRegistry -> {
                val resolverInputs = prepareResolverInstance(groundedKey)
                runtime.instrumentation.keyActivationReady(coordinate)
                keyState.fringeInstalled.complete(Unit)
                runtime.scope.launch {
                    resolverInputs.awaitAll()
                    resolveKey(
                        keyState = keyState,
                        cell = target.getCell(groundedKey),
                    )
                }
            }

            groundedKey in source.fieldValues.keys -> {
                runtime.instrumentation.keyActivationReady(coordinate)
                keyState.fringeInstalled.complete(Unit)
                runtime.scope.launch {
                    resolveKey(
                        keyState = keyState,
                        cell = target.getCell(groundedKey),
                    )
                }
            }

            else ->
                error(
                    "Resolver25 cannot activate absent passive key " +
                        "${groundedKey.field.containingType.typeName}/" +
                        groundedKey.field.fieldName,
                )
        }
        return keyState.fringeInstalled
    }

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private fun launchMergedPassiveDemand(
        keyState: KeyState,
        demand: SelectionForest,
    ): Deferred<Unit> {
        val installed = CompletableDeferred<Unit>()
        val groundedKey = keyState.groundedKey
        runtime.scope.launch {
            keyState.promiseInstalled.await()
            source.fieldValues
                .getValue(groundedKey)
                .launchNestedFringe(
                    result = target.getCell(groundedKey).getValue().await(),
                    path = path + groundedKey,
                    demand = demand,
                    potentialDemand = keyState.potentialDemand,
                ).awaitAll()
            installed.complete(Unit)
        }
        return installed
    }

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private fun launchLateOutputDemand(
        keyState: KeyState,
        demand: SelectionForest,
    ): Deferred<Unit> {
        val installed = CompletableDeferred<Unit>()
        val coordinate = path + keyState.groundedKey
        runtime.scope.launch {
            val output = keyState.outputAvailable.await()
            output.source
                .launchNestedFringe(
                    result = output.result,
                    path = coordinate,
                    demand = demand,
                    potentialDemand = keyState.potentialDemand,
                ).awaitAll()
            installed.complete(Unit)
        }
        return installed
    }

    /**
     * Prepares one grounded resolver instance without launching its resolver.
     *
     * This operation:
     * - binds arg-variables;
     * - declares path-variable bindings;
     * - contributes the complete stamped object fragment;
     * - launches path-variable readers.
     */
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private fun prepareResolverInstance(
        groundedKey: ObjectEngineResult.GroundKey,
    ): List<Deferred<Unit>> {
        if (groundedKey.arguments.argumentsContainErrorValue()) {
            return emptyList()
        }

        val resolver = world.resolverRegistry.resolver(groundedKey.field)
        val coordinate = path + groundedKey
        listOf(groundedKey).bindFromArguments(
            path = path,
            onDeclared = { variable, definition ->
                runtime.instrumentation.bindingDeclared(
                    ownerCoordinate = coordinate,
                    variable = variable,
                    source =
                        Resolver25BindingSource.FromArgument(
                            definition.argument.argumentName,
                        ),
                )
            },
            onCompleted = { variable, _, value ->
                runtime.instrumentation.bindingCompleted(
                    ownerCoordinate = coordinate,
                    variable = variable,
                    value = value,
                )
            },
        )
        val definitions = resolver.stampedPathVarDefinitions(coordinate)
        definitions.forEach { definition ->
            runtime.instrumentation.bindingDeclared(
                ownerCoordinate = coordinate,
                variable = definition.variable,
                source =
                    Resolver25BindingSource.FromObjectField(
                        definition.path.toList(),
                    ),
            )
            world.declareBinding(definition.variable)
        }
        val resolverInputs =
            addDemand(
                resolver.stampVars(coordinate),
                consumerCoordinate = coordinate,
            )
        definitions.forEach { definition ->
            runtime.scope.launch {
                val value = readProvider(definition, coordinate)
                runtime.instrumentation.bindingCompleted(
                    ownerCoordinate = coordinate,
                    variable = definition.variable,
                    value = value,
                )
                world.completeBinding(
                    definition.variable,
                    value,
                )
            }
        }
        return resolverInputs
    }

    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun readProvider(
        definition: StampedObjectPathDefinition,
        reader: List<PathComponent>,
    ): Value.Input? {
        var current = target
        definition.path.forEachIndexed { index, openKey ->
            val specializedKey =
                Selection.of(
                    key = openKey,
                    possibleTypes = setOf(current.type),
                    subselections = selectionForestOf(),
                ).objectKey(current.type)
            val groundedKey =
                ObjectEngineResult.GroundKey.of(
                    field = specializedKey.field,
                    arguments =
                        specializedKey.arguments.fetchBindings(
                            specializedKey.field.arguments,
                        ),
                )
            if (index == 0) {
                fields.getValue(groundedKey.field).awaitGroundedKey(
                    groundedKey = groundedKey,
                    requireNestedFringe = definition.path.size > 1,
                )
            }
            check(current.isCellSet(groundedKey)) {
                "Provider reader $reader cannot find installed value promise for $groundedKey"
            }
            val cell = current.getCell(groundedKey)
            diagnosticInstrumentation.cycleCheck(reader, cell)
            val value = cell.getValue().await()
            if (value == null) return null
            if (value == ErrorEngineResult) return Value.Error
            if (index == definition.path.lastIndex) {
                return value.toProviderInput()
            }
            current =
                value as? ObjectEngineResult
                    ?: error("Provider path crossed a non-object at $openKey")
        }
        error("Provider path must be nonempty")
    }

    // Traverses already-built passive output until reaching the next object occurrence whose
    // current demand crosses a resolver boundary. Lists preserve one occurrence per position.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private fun Value.Output?.launchNestedFringe(
        result: EngineResult?,
        path: List<PathComponent>,
        demand: SelectionForest,
        potentialDemand: SelectionForest,
    ): List<Deferred<Unit>> =
        when (this) {
            null,
            Value.Error,
            is Value.Simple,
            -> emptyList()

            is Value.OutputList -> {
                val resultList =
                    result as? ListEngineResult
                        ?: error("Passive list output does not match its engine result at $path")
                require(values.size == resultList.size) {
                    "Passive list output changed length at $path"
                }
                values.indices.flatMap { index ->
                    values[index].launchNestedFringe(
                        result = resultList[index].getValue().get(),
                        path = path + ListEngineResult.Index.of(index),
                        demand = demand,
                        potentialDemand = potentialDemand,
                    )
                }
            }

            is Value.Object -> {
                val resultObject =
                    result as? ObjectEngineResult
                        ?: error("Passive object output does not match its engine result at $path")
                val mergedDemand: ObjectSelectionForest = demand.merge(type)
                if (
                    mergedDemand.byKey().values.any { selection ->
                        selection.key.field in world.resolverRegistry
                    }
                ) {
                    listOf(
                        runtime.createOrchestrator(
                            path = path,
                            source = this,
                            target = resultObject,
                            initialDemand = demand,
                            potentialDemand = potentialDemand,
                        ),
                    )
                } else {
                    val mergedPotentialDemand: ObjectSelectionForest =
                        potentialDemand.merge(type)
                    mergedDemand.byKey().values.flatMap { selection ->
                        val field = selection.key.field
                        check(field.arguments.fields.isEmpty()) {
                            "Passive fringe traversal crossed argument-bearing field $field"
                        }
                        val groundedKey = ObjectEngineResult.GroundKey.of(field, emptyMap())
                        check(resultObject.isCellSet(groundedKey)) {
                            "Passive object at " +
                                path.joinToString("/") { component ->
                                    when (component) {
                                        is ObjectEngineResult.GroundKey ->
                                            "${component.field.containingType.typeName}/" +
                                                component.field.fieldName
                                        is ListEngineResult.Index -> "[${component.index}]"
                                    }
                                } +
                                " has no demanded value promise for " +
                                "${groundedKey.field.containingType.typeName}/" +
                                groundedKey.field.fieldName
                        }
                        fieldValues
                            .getValue(groundedKey)
                            .launchNestedFringe(
                                result = resultObject.getCell(groundedKey).getValue().get(),
                                path = path + groundedKey,
                                demand = selection.subselections,
                                potentialDemand =
                                    mergedPotentialDemand
                                        .byKey()
                                        .values
                                        .filter { potential ->
                                            potential.key.field == field
                                        }.flatMapToSelectionForest { potential ->
                                            potential.subselections
                                        },
                            )
                    }
                }
            }
        }

    private fun List<Deferred<Unit>>.asLatch(): Deferred<Unit> {
        if (isEmpty()) return CompletableDeferred(Unit)
        val complete = CompletableDeferred<Unit>()
        runtime.scope.launch {
            awaitAll()
            complete.complete(Unit)
        }
        return complete
    }

    // Produces one grounded field value from its materialized object fragment and sealed output
    // demand. Child object results become orchestration-ready before this value is published.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun resolveKey(
        keyState: KeyState,
        cell: EngineResult.Cell,
    ) {
        val selection = keyState.sealDemandForLaunch()
        val groundedKey = keyState.groundedKey
        val coordinate = path + groundedKey
        runtime.instrumentation.demandSealed(coordinate, selection)
        when {
            groundedKey.arguments.argumentsContainErrorValue() -> {
                runtime.instrumentation.outputAvailable(coordinate)
                keyState.outputAvailable.complete(
                    AvailableKeyOutput(Value.Error, ErrorEngineResult),
                )
                runtime.instrumentation.valuePublished(coordinate)
                cell.getValue().complete(ErrorEngineResult)
                cell.setAccessAccepted(Value.Error)
            }
            else -> {
                val resolutionSelections: SelectionForest =
                    selection.subselections.fetchSuccessorDemandDeferringTemplates()
                val fieldValue: Value.Output? =
                    if (groundedKey.field in world.resolverRegistry) {
                        val resolver = world.resolverRegistry.resolver(groundedKey.field)
                        val input: Value.Object =
                            target.materialize(
                                selections = resolver.objectFragmentAt(coordinate),
                                reader = coordinate,
                            )
                        runtime.instrumentation.resolverStarted(coordinate)
                        resolver(
                            input = input,
                            arguments = groundedKey.arguments,
                            selections = resolutionSelections,
                        ).also {
                            runtime.instrumentation.resolverFinished(coordinate)
                        }
                    } else {
                        source.fieldValues.getValue(groundedKey)
                    }
                val resolvedValue: ResolvedValue =
                    fieldValue.resolveValue(
                        path = path + groundedKey,
                        resolverDemand = resolutionSelections,
                        potentialDemand =
                            keyState.potentialDemand + selection.subselections,
                    )
                runtime.instrumentation.outputAvailable(coordinate)
                keyState.outputAvailable.complete(
                    AvailableKeyOutput(fieldValue, resolvedValue.engineResult),
                )

                val descendantsNeedingResolution: List<Deferred<Unit>> =
                    resolvedValue.objectsNeedingResolution.map { child ->
                        runtime.instrumentation.childOrchestratorRequired(
                            parentCoordinate = coordinate,
                            childPath = child.path,
                        )
                        runtime.createOrchestrator(
                            path = child.path,
                            source = child.source,
                            target = child.target,
                            initialDemand = child.selections,
                            potentialDemand = child.potentialSelections,
                        )
                    }
                descendantsNeedingResolution.awaitAll()
                runtime.instrumentation.valuePublished(coordinate)
                cell.getValue().complete(resolvedValue.engineResult)
                cell.setAccessAccepted(Value.Boolean.of(true))
            }
        }
    }

    private class FieldState(
        val field: Schema.ObjectField,
    ) {
        private var potentialSelections: SelectionForest = selectionForestOf()
        val groundedKeys: MutableMap<ObjectEngineResult.GroundKey, KeyState> = linkedMapOf()
        private val groundedKeyAvailable:
            MutableMap<ObjectEngineResult.GroundKey, CompletableDeferred<KeyState>> =
            linkedMapOf()

        @Synchronized
        fun addPotentialSelection(selection: Selection) {
            check(groundedKeys.isEmpty()) {
                "Potential demand arrived after grounded-key activation for $field"
            }
            potentialSelections += selectionForestOf(selection)
        }

        context(world: Assumptions)
        @Synchronized
        fun activate(
            groundedKey: ObjectEngineResult.GroundKey,
            groundedSelection: ObjectSelection,
            concreteType: Schema.ObjectType,
        ): KeyActivation {
            groundedKeys[groundedKey]?.let { keyState ->
                return KeyActivation(
                    keyState = keyState,
                    created = false,
                    mergedBeforeLaunch =
                        keyState.mergeDemandBeforeLaunch(
                            groundedSelection = groundedSelection,
                            concreteType = concreteType,
                        ),
                )
            }
            check(groundedKey.field == field)
            // Open keys may still converge here; already-ground keys cannot contribute across keys.
            val keyPotentialSubselections: SelectionForest =
                potentialSelections
                    .flatMap { potentialSelection ->
                        val potentialKey = potentialSelection.objectKey(concreteType)
                        val potentialArguments = potentialKey.arguments
                        if (
                            potentialArguments !is Value.Arguments ||
                            ObjectEngineResult.GroundKey.of(
                                potentialKey.field,
                                potentialArguments,
                            ) == groundedKey
                        ) {
                            potentialSelection.subselections
                        } else {
                            selectionForestOf()
                        }
                    }
            val initialDemand: ObjectSelection =
                selectionForestOf(
                    Selection.of(
                        key = groundedKey,
                        possibleTypes = groundedSelection.possibleTypes,
                        subselections =
                            groundedSelection.subselections +
                                keyPotentialSubselections
                                    .projectionDemandDeferringTemplates(),
                    ),
                ).merge(concreteType)
                    .byGroundKey()
                    .getValue(groundedKey)
            val keyState =
                KeyState(
                    groundedKey = groundedKey,
                    initialDemand = initialDemand,
                    potentialDemand = keyPotentialSubselections,
                )
            groundedKeys[groundedKey] = keyState
            groundedKeyAvailable
                .getOrPut(groundedKey, ::CompletableDeferred)
                .complete(keyState)
            return KeyActivation(
                keyState = keyState,
                created = true,
                mergedBeforeLaunch = true,
            )
        }

        suspend fun awaitGroundedKey(
            groundedKey: ObjectEngineResult.GroundKey,
            requireNestedFringe: Boolean,
        ) {
            val available =
                synchronized(this) {
                    groundedKeys[groundedKey]?.let { keyState ->
                        CompletableDeferred(keyState)
                    } ?: groundedKeyAvailable.getOrPut(
                        groundedKey,
                        ::CompletableDeferred,
                    )
                }
            val keyState = available.await()
            keyState.promiseInstalled.await()
            if (requireNestedFringe) {
                keyState.fringeInstalled.await()
            }
        }
    }

    private class KeyState(
        val groundedKey: ObjectEngineResult.GroundKey,
        initialDemand: ObjectSelection,
        val potentialDemand: SelectionForest,
    ) {
        // Opens as soon as this grounded key can be looked up in the containing OER.
        val promiseInstalled: CompletableDeferred<Unit> = CompletableDeferred()

        // Opens after reaching this resolver boundary or installing its passive nested fringe.
        val fringeInstalled: CompletableDeferred<Unit> = CompletableDeferred()

        // Opens before public value publication so late demand can deepen the returned output.
        val outputAvailable: CompletableDeferred<AvailableKeyOutput> = CompletableDeferred()
        private var openDemand: ObjectSelection = initialDemand
        private var sealedDemand: ObjectSelection? = null
        private var launched: Boolean = false

        context(world: Assumptions)
        @Synchronized
        fun mergeDemandBeforeLaunch(
            groundedSelection: ObjectSelection,
            concreteType: Schema.ObjectType,
        ): Boolean {
            if (sealedDemand != null) return false
            openDemand =
                (selectionForestOf(openDemand) + selectionForestOf(groundedSelection))
                    .merge(concreteType)
                    .byGroundKey()
                    .getValue(groundedKey)
            return true
        }

        @Synchronized
        fun demandSnapshot(): ObjectSelection = sealedDemand ?: openDemand

        @Synchronized
        fun sealDemandForLaunch(): ObjectSelection {
            check(!launched) {
                "Resolver25 launched grounded key more than once: $groundedKey"
            }
            launched = true
            return openDemand.also { demand ->
                sealedDemand = demand
            }
        }
    }

    private class KeyActivation(
        val keyState: KeyState,
        val created: Boolean,
        val mergedBeforeLaunch: Boolean,
    )

    private class AvailableKeyOutput(
        val source: Value.Output?,
        val result: EngineResult?,
    )
}

/**
 * Converts one resolver output to its passive engine-result shape and identifies object results
 * that still contain active resolver demand.
 */
context(world: Assumptions)
private suspend fun Value.Output?.resolveValue(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
    potentialDemand: SelectionForest,
): ResolvedValue =
    when (this) {
        null -> ResolvedValue(null, emptyList())
        Value.Error -> ResolvedValue(ErrorEngineResult, emptyList())
        is Value.Simple -> ResolvedValue(toEngineResult(), emptyList())
        is Value.Object ->
            resolveObjectValue(
                path = path,
                resolverDemand = resolverDemand,
                potentialDemand = potentialDemand,
            )
        is Value.OutputList -> {
            val resolvedElements: List<ResolvedValue> =
                values.mapIndexed { index, value ->
                    value.resolveValue(
                        path = path + ListEngineResult.Index.of(index),
                        resolverDemand = resolverDemand,
                        potentialDemand = potentialDemand,
                    )
                }
            ResolvedValue(
                engineResult =
                    ListEngineResult.of(
                        typeExpr = typeExpr,
                        values = resolvedElements.map(ResolvedValue::engineResult),
                    ),
                objectsNeedingResolution =
                    resolvedElements.flatMap(ResolvedValue::objectsNeedingResolution),
            )
        }
    }

// Selects passive output fields and retains this object instance when any selected key crosses a
// resolver boundary.
context(world: Assumptions)
private suspend fun Value.Object.resolveObjectValue(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
    potentialDemand: SelectionForest,
): ResolvedValue {
    val engineResult: ObjectEngineResult =
        type.newObjectResult()
    val mergedDemand: ObjectSelectionForest =
        resolverDemand.mergeWithVariables(engineResult).first
    val resolverDemandByGroundedKey: Map<ObjectEngineResult.GroundKey, ObjectSelection> =
        mergedDemand.byGroundKey()
    val closedPotentialDemand: ObjectSelectionForest =
        type.closeStructuralDemand(potentialDemand)
    val potentialSubselectionsByField: Map<Schema.ObjectField, SelectionForest> =
        closedPotentialDemand
            .byKey()
            .values
            .groupBy { selection -> selection.key.field }
            .mapValues { (_, selections) ->
                selections.flatMapToSelectionForest { selection ->
                    selection.subselections
                }
            }
    val unselectedGroundedKeys: Set<ObjectEngineResult.GroundKey> =
        fieldValues.keys - resolverDemandByGroundedKey.keys
    require(unselectedGroundedKeys.isEmpty()) {
        "Selective resolver output ${type.typeName} contains unselected fields: " +
            unselectedGroundedKeys.joinToString { groundedKey ->
                groundedKey.field.fieldName
            }
    }

    val selectedGroundedKeys: Set<ObjectEngineResult.GroundKey> =
        resolverDemandByGroundedKey.keys
            .filterTo(linkedSetOf()) { groundedKey ->
                groundedKey.field !in world.resolverRegistry
            }
    val resolvedFields: List<ResolvedField> =
        selectedGroundedKeys.map { groundedKey ->
            val resolvedValue: ResolvedValue =
                fieldValues
                    .getValue(groundedKey)
                    .resolveValue(
                        path = path + groundedKey,
                        resolverDemand =
                            resolverDemandByGroundedKey
                                .getValue(groundedKey)
                                .subselections,
                        potentialDemand =
                            potentialSubselectionsByField[groundedKey.field]
                                ?: selectionForestOf(),
                    )
            ResolvedField(groundedKey, resolvedValue)
        }
    resolvedFields.forEach { resolvedField ->
        engineResult.reserveCell(resolvedField.groundedKey).also { cell ->
            cell.setValue(resolvedField.value.engineResult)
            cell.setAccessAccepted(Value.Boolean.of(true))
        }
    }
    val localResolution: ObjectResolution? =
        if (
            resolverDemandByGroundedKey.keys.any { groundedKey ->
                groundedKey.field in world.resolverRegistry
            }
        ) {
            ObjectResolution(
                path = path,
                source = this,
                selections = resolverDemand,
                potentialSelections = closedPotentialDemand,
                target = engineResult,
            )
        } else {
            null
        }
    val descendantResolutions: List<ObjectResolution> =
        resolvedFields.flatMap { resolvedField ->
            resolvedField.value.objectsNeedingResolution
        }
    return ResolvedValue(
        engineResult = engineResult,
        objectsNeedingResolution =
            localResolution?.let(::listOf) ?: descendantResolutions,
    )
}

private class ResolvedValue(
    val engineResult: EngineResult?,
    val objectsNeedingResolution: List<ObjectResolution>,
)

private class ObjectResolution(
    val path: List<PathComponent>,
    val source: Value.Object,
    val selections: SelectionForest,
    val potentialSelections: SelectionForest,
    val target: ObjectEngineResult,
)

private class ResolvedField(
    val groundedKey: ObjectEngineResult.GroundKey,
    val value: ResolvedValue,
)

private fun EngineResult.toProviderInput(): Value.Input =
    when (this) {
        ErrorEngineResult -> Value.Error
        is SimpleEngineResult -> toValue()
        is ListEngineResult -> toProviderInputList()
        is ObjectEngineResult ->
            error("A path-variable provider cannot terminate at an object")
    }

@Suppress("UNCHECKED_CAST")
private fun ListEngineResult.toProviderInputList(): Value.InputList {
    require(typeExpr.baseType is Schema.InputType) {
        "A path-variable provider list must contain input-compatible simple values"
    }
    return Value.InputList.of(
        typeExpr = typeExpr as TypeExpr<Schema.InputType>,
        values = map { cell -> cell.getValue().get()?.toProviderInput() },
    )
}
