package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.OpenArguments
import model.PathComponent
import model.Promise
import model.Schema
import model.Selection
import model.SelectionForest
import model.SelectionStamp
import model.Value
import model.fetchBindings
import model.groundKey
import model.localizeTopLevelSelectionStamps
import model.merge
import model.selectionForestOf
import model.usedVariables
import model.registry.FieldResolver
import model.registry.SelectionStampedVariableDefinition
import model.registry.StampedObjectPathDefinition
import model.registry.VariableDefinition
import semantics.ObjectResolution
import semantics.ResolvedValue
import semantics.RuntimeSupport
import semantics.correctresolution.argumentsContainErrorValue
import semantics.resolveValue
import kotlin.coroutines.CoroutineContext

/**
 * Resolves selective demand once per ordinary or provenance-stamped resolver instance.
 *
 * Pre-grounded selections coalesce by ordinary ground key. Every variable-bearing selection in a
 * resolver object fragment retains its nonempty provenance and therefore resolves independently.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object =
    resolve(
        selections = selections,
        coroutineContext = resolver26CoroutineContext(),
    )

// Resolves one request with every Resolver26 coroutine inheriting the supplied context.
context(world: Assumptions)
internal fun Value.Object.resolve(
    selections: SelectionForest,
    coroutineContext: CoroutineContext,
    applicationObserver: Resolver26ApplicationObserver = {},
): EngineResult.Object {
    require(world.selectiveResolvers) {
        "Resolver26 requires selective resolvers"
    }
    context(RuntimeSupport.cycleChecking()) {
        val result: EngineResult.Object =
            EngineResult.Object.of(
                type = type,
                mutable = true,
            )
        return runBlocking(coroutineContext) {
            withTimeout(15_000) {
                coroutineScope {
                    val runtime =
                        ResolverRuntime(
                            requestScope = this,
                            applicationObserver = applicationObserver,
                        )
                    orchestrateObject(
                        path = emptyList(),
                        source = this@resolve,
                        initialDemand = selections,
                        target = result,
                        runtime = runtime,
                    )
                }
                result
            }
        }
    }
}

/** Owns request lifetime without using task completion as cross-task readiness. */
private class ResolverRuntime(
    private val requestScope: CoroutineScope,
    private val applicationObserver: Resolver26ApplicationObserver,
) {
    // Launches an orchestration task as a direct child of the request.
    fun launchOrchestrationTask(block: suspend () -> Unit) {
        requestScope.launch {
            block()
        }
    }

    // Launches a field-resolution task as a direct child of the request.
    fun launchFieldResolutionTask(block: suspend () -> Unit) {
        requestScope.launch {
            block()
        }
    }

    fun observeApplication(observation: Resolver26ApplicationObservation) {
        applicationObserver(observation)
    }
}

// Closes one OER's demand, installs every active field promise, and then freezes its key set.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun orchestrateObject(
    path: List<PathComponent>,
    source: Value.Object,
    initialDemand: SelectionForest,
    target: EngineResult.Object,
    runtime: ResolverRuntime,
) {
    // Expands resolver object fragments to a fixpoint and returns one unique key map for this OER.
    fun closeInputDemand(): CloseInputDemandResult {
        val localizedDemand: LocalizeTopLevelStampsResult =
            initialDemand.localizeTopLevelStamps(path)
        var accumulatedDemand: SelectionForest = localizedDemand.demand
        val expansions: MutableMap<Value.ObjectKey, ResolverExpansion> = linkedMapOf()
        val pathVariableDefinitions: MutableList<StampedObjectPathDefinition> = mutableListOf()

        while (true) {
            val mergedDemand: ObjectSelectionForest = accumulatedDemand.merge(source.type)
            val newResolverSelections: Map<Value.ObjectKey, ObjectSelection> =
                mergedDemand
                    .byKey()
                    .filter { (objectKey, _) ->
                        objectKey.field in world.resolverRegistry &&
                            objectKey !in expansions
                    }
            if (newResolverSelections.isEmpty()) {
                check(
                    mergedDemand
                        .byKey()
                        .filterKeys { objectKey ->
                            objectKey.field in world.resolverRegistry
                        }.keys == expansions.keys,
                ) {
                    "Resolver26 closed demand and resolver expansions are misaligned"
                }
                val selectionStamps: List<SelectionStamp> =
                    mergedDemand.keys().mapNotNull { objectKey -> objectKey.selectionStamp() }
                check(selectionStamps.size == selectionStamps.toSet().size) {
                    "Resolver26 closed demand contains duplicate selection stamps"
                }
                return CloseInputDemandResult(
                    demand = mergedDemand,
                    expansions = expansions,
                    bindingAliases = localizedDemand.bindingAliases,
                    pathVariableDefinitions = pathVariableDefinitions,
                )
            }

            newResolverSelections.forEach { (objectKey, _) ->
                val resolver: FieldResolver =
                    world.resolverRegistry.resolver(objectKey.field)
                if (
                    objectKey is Value.GroundKey &&
                    objectKey.arguments.argumentsContainErrorValue()
                ) {
                    check(
                        expansions.put(
                            objectKey,
                            ResolverExpansion(
                                ownerKey = objectKey,
                                resolver = resolver,
                                inputDemand = selectionForestOf(),
                                variableDefinitions = emptyList(),
                            ),
                        ) == null,
                    ) {
                        "Resolver26 expanded error-valued object key twice: $objectKey"
                    }
                    return@forEach
                }
                val ownerStamp: SelectionStamp? = objectKey.selectionStamp()
                val resolverPath: List<PathComponent> =
                    if (ownerStamp == null) {
                        path + (objectKey as Value.GroundKey)
                    } else {
                        ownerStamp.resolverPath
                    }
                val inputDemand: SelectionForest =
                    if (ownerStamp == null) {
                        resolver.stamp(resolverPath)
                    } else {
                        resolver.stampFrom(ownerStamp)
                    }
                val definitions: List<SelectionStampedVariableDefinition> =
                    if (ownerStamp == null) {
                        resolver.selectionStampedVariableDefinitions(resolverPath)
                    } else {
                        resolver.selectionStampedVariableDefinitionsFrom(ownerStamp)
                    }
                val expansion: ResolverExpansion =
                    ResolverExpansion(
                        ownerKey = objectKey,
                        resolver = resolver,
                        inputDemand = inputDemand,
                        variableDefinitions = definitions,
                    )
                check(expansions.put(objectKey, expansion) == null) {
                    "Resolver26 expanded object key twice: $objectKey"
                }

                definitions.forEach { stampedDefinition ->
                    when (val definition = stampedDefinition.definition) {
                        is VariableDefinition.FromArgument -> Unit
                        is VariableDefinition.FromObjectField -> {
                            require(
                                definition.path.all { providerKey ->
                                    providerKey.field.arguments.fields.isEmpty()
                                },
                            ) {
                                "Resolver26 requires argument-free FromObjectField provider paths"
                            }
                            pathVariableDefinitions +=
                                StampedObjectPathDefinition(
                                    variable = stampedDefinition.variable,
                                    path = definition.path,
                                )
                        }
                    }
                }
                accumulatedDemand += inputDemand
            }
        }
    }

    require(source.type == target.type) {
        "Source type ${source.type.typeName} does not match result type ${target.type.typeName}"
    }

    val closed: CloseInputDemandResult = closeInputDemand()
    closed.prepareBindings()
    closed.demand.byKey().forEach { (objectKey, selection) ->
        if (objectKey.field !in world.resolverRegistry) {
            val groundKey: Value.GroundKey =
                objectKey as? Value.GroundKey
                    ?: error("Resolver26 found open arguments on passive key $objectKey")
            val sourceValue: Value.Output? = source.fieldValues.getValue(groundKey)
            if (!target.isValueSet(groundKey)) {
                val resolvedValue: ResolvedValue =
                    sourceValue.resolveValue(
                        path = path + groundKey,
                        resolverDemand = selection.subselections,
                    )
                target.setValue(groundKey, resolvedValue.engineResult)
            }
            launchPassiveChildOrchestrations(
                path = path + groundKey,
                source = sourceValue,
                target = target.getValue(groundKey).get(),
                initialDemand = selection.subselections,
                runtime = runtime,
            )
        }
    }

    coroutineScope {
        closed.bindingAliases.forEach { alias ->
            launch {
                world.completeBinding(
                    alias.localizedVariable,
                    world.fetchBinding(alias.sourceVariable),
                )
            }
        }
        closed.pathVariableDefinitions.forEach { definition ->
            launch {
                val reader: List<PathComponent> =
                    (definition.variable as Value.Variable.SelectionStamped)
                        .selectionStamp
                        .resolverPath
                val value: Value.Input? =
                    target.readProvider(
                        definition = definition,
                        reader = reader,
                    )
                world.completeBinding(definition.variable, value)
            }
        }
        closed.demand.byKey().forEach { (objectKey, selection) ->
            when {
                objectKey.field in world.resolverRegistry ->
                    launch {
                        installAndLaunchFieldResolver(
                            path = path,
                            selection = selection,
                            expansion = closed.expansions.getValue(objectKey),
                            target = target,
                            runtime = runtime,
                        )
                    }

                else ->
                    check(objectKey is Value.GroundKey && target.isValueSet(objectKey)) { // TODO: isValueSet should tolerate Value.Key as input
                        "Resolver26 passive key $objectKey was not materialized by resolveValue"
                    }
            }
        }
    }
    target.freeze()
}

// Grounds one final object key, claims its OER promise, and starts its field-resolution task.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun installAndLaunchFieldResolver(
    path: List<PathComponent>,
    selection: ObjectSelection,
    expansion: ResolverExpansion,
    target: EngineResult.Object,
    runtime: ResolverRuntime,
) {
    val groundedSelection: ObjectSelection =
        selectionForestOf(selection)
            .merge(target.type)
            .fetchBindings()
            .byGroundKey()
            .values
            .single()
    val groundKey = groundedSelection.groundKey()
    check(groundKey.field in world.resolverRegistry) {
        "Resolver26 attempted to install passive key $groundKey"
    }
    expansion.completeFromArgumentBindings(groundKey)

    val valuePromise = target.createValuePromise(groundKey)
    diagnosticInstrumentation.registerWriter(
        target = target,
        key = groundKey,
        writer = path + groundKey,
    )
    runtime.launchFieldResolutionTask {
        resolveField(
            path = path,
            selection = groundedSelection,
            expansion = expansion,
            target = target,
            valuePromise = valuePromise,
            runtime = runtime,
        )
    }
}

// Declares every open binding and immediately binds definitions owned by pre-grounded keys.
context(world: Assumptions)
private fun CloseInputDemandResult.prepareBindings() {
    check(!bindingsPrepared) {
        "Resolver26 closed demand prepared its bindings twice"
    }
    bindingsPrepared = true
    bindingAliases.forEach { alias ->
        world.declareBinding(alias.localizedVariable)
    }
    expansions.values.forEach { expansion ->
        expansion.variableDefinitions.forEach { stampedDefinition ->
            when (val definition = stampedDefinition.definition) {
                is VariableDefinition.FromArgument ->
                    if (expansion.ownerKey is Value.GroundKey) {
                        world.bindVariable(
                            stampedDefinition.variable,
                            expansion.ownerKey.arguments.fieldValues.getValue(
                                definition.argument.argumentName,
                            ),
                        )
                    } else {
                        world.declareBinding(stampedDefinition.variable)
                    }

                is VariableDefinition.FromObjectField ->
                    world.declareBinding(stampedDefinition.variable)
            }
        }
    }
}

// Extends top-level stamped selections through this concrete OER path, including list indices.
private fun SelectionForest.localizeTopLevelStamps(
    path: List<PathComponent>,
): LocalizeTopLevelStampsResult {
    if (path.isEmpty()) {
        return LocalizeTopLevelStampsResult(this, emptyList())
    }
    val bindingAliases = linkedSetOf<BindingAlias>()
    val localizedDemand: SelectionForest =
        flatMap { selection ->
            val localizedSelection: Selection =
                selectionForestOf(selection)
                    .localizeTopLevelSelectionStamps(path)
                    .single()
            val sourceVariables:
                Map<Pair<Schema.ObjectField, String>, Value.Variable.SelectionStamped> =
                selection.key
                    .selectionStampedVariables()
                    .associateBy { variable -> variable.field to variable.variableName }
            localizedSelection.key
                .selectionStampedVariables()
                .forEach { localizedVariable ->
                    val sourceVariable: Value.Variable.SelectionStamped =
                        sourceVariables.getValue(
                            localizedVariable.field to localizedVariable.variableName,
                        )
                    if (sourceVariable != localizedVariable) {
                        bindingAliases +=
                            BindingAlias(
                                sourceVariable = sourceVariable,
                                localizedVariable = localizedVariable,
                            )
                    }
                }
            selectionForestOf(localizedSelection)
        }
    return LocalizeTopLevelStampsResult(
        demand = localizedDemand,
        bindingAliases = bindingAliases.toList(),
    )
}

// Returns every selection-stamped argument or provider-marker variable carried by this key.
private fun Value.Key.selectionStampedVariables(): Set<Value.Variable.SelectionStamped> =
    buildSet {
        addAll(arguments.usedVariables().filterIsInstance<Value.Variable.SelectionStamped>())
        val marker = (this@selectionStampedVariables as? Value.VariableKey)?.variableDefinedByThisKey
        if (marker is Value.Variable.SelectionStamped) add(marker)
    }

// Returns the occurrence stamp carried by an open or already-grounded stamped object key.
private fun Value.Key.selectionStamp(): SelectionStamp? =
    when (this) {
        is Value.GroundKey.Stamped -> selectionStamp
        else -> (arguments as? OpenArguments.Stamped)?.selectionStamp
    }

// Completes variables defined from this resolver instance's now-ground arguments.
context(world: Assumptions)
private fun ResolverExpansion.completeFromArgumentBindings(groundKey: Value.GroundKey) {
    if (ownerKey is Value.GroundKey) return
    variableDefinitions.forEach { stampedDefinition ->
        if (stampedDefinition.definition !is VariableDefinition.FromArgument) {
            return@forEach
        }
        val definition = stampedDefinition.definition as VariableDefinition.FromArgument
        world.completeBinding(
            stampedDefinition.variable,
            groundKey.arguments.fieldValues.getValue(definition.argument.argumentName),
        )
    }
}

// Invokes one active resolver, constructs its passive result, and publishes its value promise.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun resolveField(
    path: List<PathComponent>,
    selection: ObjectSelection,
    expansion: ResolverExpansion,
    target: EngineResult.Object,
    valuePromise: Promise<EngineResult?>,
    runtime: ResolverRuntime,
) {
    val groundKey = selection.groundKey()
    check(groundKey.field in world.resolverRegistry) {
        "Resolver26 resolveField received passive key $groundKey"
    }
    if (groundKey.arguments.argumentsContainErrorValue()) {
        valuePromise.complete(Value.Error)
        return
    }

    val coordinate = path + groundKey
    val constructionDemand: SelectionForest = selection.subselections
    val invocationDemand: SelectionForest = constructionDemand.successorDemand()
    val input: Value.Object =
        target.materializeResolverInput(
            selections = expansion.inputDemand,
            reader = coordinate,
            resultPath = path,
        )
    val resolverArguments: Value.Arguments =
        Value.Arguments.of(
            field = groundKey.field,
            fields = groundKey.arguments.fieldValues,
        )
    runtime.observeApplication(
        Resolver26ApplicationObservation(
            occurrencePath = coordinate,
            field = groundKey.field,
            input = input,
            arguments = resolverArguments,
            suppliedDemand = invocationDemand,
        ),
    )
    val fieldValue: Value.Output? =
        expansion.resolver(
            input = input,
            arguments = resolverArguments,
            selections = invocationDemand,
        )
    val resolvedValue: ResolvedValue =
        fieldValue.resolveValue(
            path = coordinate,
            resolverDemand = invocationDemand,
        )

    resolvedValue.objectOccurrences
        .filter { occurrence -> occurrence.isRootOfOutputAt(coordinate) }
        .forEach { child ->
            runtime.launchOrchestrationTask {
                orchestrateObject(
                    path = child.path,
                    source = child.source,
                    initialDemand = constructionDemand,
                    target = child.target,
                    runtime = runtime,
                )
            }
        }
    valuePromise.complete(resolvedValue.engineResult)
}

// Returns whether this occurrence is a top-level object or list element in one resolver output.
private fun ObjectResolution.isRootOfOutputAt(
    coordinate: List<PathComponent>,
): Boolean =
    path.size >= coordinate.size &&
        path.take(coordinate.size) == coordinate &&
        path.drop(coordinate.size).all { component -> component is Value.ListIndex }

// Starts orchestration for object values already materialized by resolveValue.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private fun launchPassiveChildOrchestrations(
    path: List<PathComponent>,
    source: Value.Output?,
    target: EngineResult?,
    initialDemand: SelectionForest,
    runtime: ResolverRuntime,
) {
    when (source) {
        null -> {
            check(target == null) {
                "Resolver26 passive null source has non-null result at $path"
            }
        }

        Value.Error -> {
            check(target == Value.Error) {
                "Resolver26 passive error source has different result at $path"
            }
        }

        is Value.Simple -> {
            check(target == source) {
                "Resolver26 passive simple source has different result at $path"
            }
        }

        is Value.Object -> {
            check(target is EngineResult.Object) {
                "Resolver26 passive object source has non-object result at $path"
            }
            runtime.launchOrchestrationTask {
                orchestrateObject(
                    path = path,
                    source = source,
                    initialDemand = initialDemand,
                    target = target,
                    runtime = runtime,
                )
            }
        }

        is Value.OutputList -> {
            check(target is EngineResult.List && target.size == source.values.size) {
                "Resolver26 passive list source has different result shape at $path"
            }
            source.values.forEachIndexed { index, value ->
                launchPassiveChildOrchestrations(
                    path = path + Value.ListIndex.of(index),
                    source = value,
                    target = target[index],
                    initialDemand = initialDemand,
                    runtime = runtime,
                )
            }
        }
    }
}

private data class ResolverExpansion(
    val ownerKey: Value.ObjectKey,
    val resolver: FieldResolver,
    val inputDemand: SelectionForest,
    val variableDefinitions: List<SelectionStampedVariableDefinition>,
)

private class CloseInputDemandResult(
    val demand: ObjectSelectionForest,
    val expansions: Map<Value.ObjectKey, ResolverExpansion>,
    val bindingAliases: List<BindingAlias>,
    val pathVariableDefinitions: List<StampedObjectPathDefinition>,
) {
    var bindingsPrepared: Boolean = false
}

private data class LocalizeTopLevelStampsResult(
    val demand: SelectionForest,
    val bindingAliases: List<BindingAlias>,
)

private data class BindingAlias(
    val sourceVariable: Value.Variable.SelectionStamped,
    val localizedVariable: Value.Variable.SelectionStamped,
)
