package semantics.resolver26

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.EngineResultCell
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.Arguments
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.Promise
import model.Schema
import model.Selection
import model.SelectionForest
import model.Stamp
import model.TypeExpr
import model.schemaType
import viaduct.engine.api.EngineObjectData
import model.VariableBinding
import model.fetchBindings
import model.groundKey
import model.localizeTopLevelSelectionStamps
import model.materializeSelectionForestOf
import model.merge
import model.selectionForestOf
import model.toEngineResult
import model.usedVariables
import model.variableArgumentNames
import model.variableSourceSelectionStamps
import model.registry.FieldResolver
import model.registry.ResolverObjectFragment
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
 * Resolves selective demand once per ordinary or occurrence-stamped resolver instance.
 *
 * Pre-grounded selections coalesce by ordinary ground key. Every variable-bearing selection in a
 * resolver object fragment retains its occurrence lineage and therefore resolves
 * independently.
 */
context(world: Assumptions)
fun resolve(selections: SelectionForest): ObjectEngineResult =
    resolve(
        selections = selections,
        coroutineContext = resolver26CoroutineContext(),
    )

context(world: Assumptions)
internal fun resolve(
    selections: SelectionForest,
    coroutineContext: CoroutineContext,
    applicationObserver: Resolver26ApplicationObserver = {},
): ObjectEngineResult {
    require(world.selectiveResolvers) {
        "Resolver26 requires selective resolvers"
    }
    val source = world.resolverRegistry.resolveRootQuery()
    context(RuntimeSupport.cycleChecking()) {
        val result: ObjectEngineResult =
            ObjectEngineResult.of(
                type = source.schemaType,
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
                        source = source,
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

context(world: Assumptions)
internal fun resolveObserved(
    selections: SelectionForest,
    applicationObserver: Resolver26ApplicationObserver,
): ObjectEngineResult =
    resolve(
        selections = selections,
        coroutineContext = resolver26CoroutineContext(),
        applicationObserver = applicationObserver,
    )

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
    source: EngineObjectData.Sync,
    initialDemand: SelectionForest,
    target: ObjectEngineResult,
    runtime: ResolverRuntime,
) {
    // Expands resolver object fragments to a fixpoint and returns one unique key map for this OER.
    fun closeInputDemand(): CloseInputDemandResult {
        val localizedDemand: LocalizeTopLevelStampsResult =
            initialDemand.localizeTopLevelStamps(path)
        var accumulatedDemand: SelectionForest = localizedDemand.demand
        val expansions: MutableMap<ObjectEngineResult.ObjectKey, ResolverExpansion> = linkedMapOf()
        val pathVariableDefinitions: MutableList<StampedObjectPathDefinition> = mutableListOf()

        while (true) {
            val mergedDemand: ObjectSelectionForest = accumulatedDemand.merge(source.schemaType)
            val newResolverSelections: Map<ObjectEngineResult.ObjectKey, ObjectSelection> =
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
                val selectionStamps: List<Stamp.Occurrence> =
                    mergedDemand.keys().mapNotNull { key -> key.stamp as? Stamp.Occurrence }
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
                    objectKey is ObjectEngineResult.GroundKey &&
                    objectKey.arguments.argumentsContainErrorValue()
                ) {
                    check(
                        expansions.put(
                            objectKey,
                            ResolverExpansion(
                                ownerKey = objectKey,
                                resolver = resolver,
                                inputDemand = selectionForestOf(),
                                inputMaterializeSelections = materializeSelectionForestOf(),
                                variableDefinitions = emptyList(),
                            ),
                        ) == null,
                    ) {
                        "Resolver26 expanded error-valued object key twice: $objectKey"
                    }
                    return@forEach
                }
                val ownerStamp: Stamp.Occurrence? = objectKey.stamp as? Stamp.Occurrence
                val resolverPath: List<PathComponent> =
                    if (ownerStamp == null) {
                        path + (objectKey as ObjectEngineResult.GroundKey)
                    } else {
                        ownerStamp.resolverPath
                    }
                val resolverStamp: Stamp.Occurrence =
                    ownerStamp
                        ?: Stamp.Occurrence.of(resolverPath = resolverPath)
                val objectFragment: ResolverObjectFragment =
                    resolver.instantiateObjectFragment(resolverStamp)
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
                        inputDemand = objectFragment.constructionSelections,
                        inputMaterializeSelections = objectFragment.materializeSelections,
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
                                StampedObjectPathDefinition.of(
                                    variable = stampedDefinition.variable,
                                    path = definition.path,
                                )
                        }
                    }
                }
                accumulatedDemand += objectFragment.constructionSelections
            }
        }
    }

    require(source.schemaType == target.type) {
        "Source type ${source.schemaType.name} does not match result type ${target.type.name}"
    }

    val closed: CloseInputDemandResult = closeInputDemand()
    closed.prepareBindings()
    closed.demand.byKey().forEach { (objectKey, selection) ->
        if (objectKey.field !in world.resolverRegistry) {
            val groundKey: ObjectEngineResult.GroundKey =
                objectKey as? ObjectEngineResult.GroundKey
                    ?: error("Resolver26 found open arguments on passive key $objectKey")
            val arguments = groundKey.arguments
            require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
                "Resolver26 passive field ${groundKey.field.containingDef.name}/" +
                    "${groundKey.field.name} must be argumentless"
            }
            val sourceValue: EngineOutputData? =
                source.get(groundKey.field.name)
            if (!target.isCellSet(groundKey)) {
                val resolvedValue: ResolvedValue =
                    sourceValue.resolveValue(
                        expectedType = groundKey.field.type,
                        path = path + groundKey,
                        resolverDemand = selection.subselections,
                    )
                target.reserveCell(groundKey).also { cell ->
                    cell.setValue(resolvedValue.engineResult)
                    cell.setAccessResult(true)
                }
            }
            launchPassiveChildOrchestrations(
                path = path + groundKey,
                source = sourceValue,
                target = target.getCell(groundKey).getValue().get(),
                expectedType = groundKey.field.type,
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
                    requireNotNull(definition.variable.stamp)
                        .resolverPath
                val binding: VariableBinding =
                    target.readProvider(
                        definition = definition,
                        reader = reader,
                    )
                world.completeBinding(definition.variable, binding)
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
                    check(objectKey is ObjectEngineResult.GroundKey && target.isCellSet(objectKey)) { // TODO: isValueSet should tolerate ObjectEngineResult.Key as input
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
    target: ObjectEngineResult,
    runtime: ResolverRuntime,
) {
    val variableArgumentCount = selection.key.arguments.variableArgumentNames().size
    val variableSourceSelectionStamps =
        selection.key.arguments.variableSourceSelectionStamps()
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

    val cell = target.reserveCell(groundKey)
    cell.createValuePromise()
    diagnosticInstrumentation.registerWriter(
        cell = cell,
        writer = path + groundKey,
    )
    runtime.launchFieldResolutionTask {
        resolveField(
            path = path,
            selection = groundedSelection,
            expansion = expansion,
            target = target,
            cell = cell,
            variableArgumentCount = variableArgumentCount,
            variableSourceSelectionStamps = variableSourceSelectionStamps,
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
                    if (expansion.ownerKey is ObjectEngineResult.GroundKey) {
                        world.bindVariable(
                            stampedDefinition.variable,
                            expansion.ownerKey.arguments.bindingFor(
                                definition.argument.name,
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
                Map<Pair<Schema.ObjectField, String>, Arguments.Variable> =
                selection.key
                    .selectionStampedVariables()
                    .associateBy { variable -> variable.field to variable.variableName }
            localizedSelection.key
                .selectionStampedVariables()
                .forEach { localizedVariable ->
                    val sourceVariable: Arguments.Variable =
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
private fun ObjectEngineResult.Key.selectionStampedVariables(): Set<Arguments.Variable> =
    buildSet {
        addAll(
            arguments
                .usedVariables()
                .filter { variable -> variable.stamp?.sourceKey != null },
        )
        val marker = (this@selectionStampedVariables as? ObjectEngineResult.VariableKey)?.variableDefinedByThisKey
        if (marker?.stamp?.sourceKey != null) add(marker)
    }

// Completes variables defined from this resolver instance's now-ground arguments.
context(world: Assumptions)
private fun ResolverExpansion.completeFromArgumentBindings(groundKey: ObjectEngineResult.GroundKey) {
    if (ownerKey is ObjectEngineResult.GroundKey) return
    variableDefinitions.forEach { stampedDefinition ->
        if (stampedDefinition.definition !is VariableDefinition.FromArgument) {
            return@forEach
        }
        val definition = stampedDefinition.definition as VariableDefinition.FromArgument
        world.completeBinding(
            stampedDefinition.variable,
            groundKey.arguments.bindingFor(definition.argument.name),
        )
    }
}

// Invokes one active resolver, constructs its passive result, and publishes its value promise.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private suspend fun resolveField(
    path: List<PathComponent>,
    selection: ObjectSelection,
    expansion: ResolverExpansion,
    target: ObjectEngineResult,
    cell: EngineResultCell,
    variableArgumentCount: Int,
    variableSourceSelectionStamps: Set<Stamp.Occurrence>,
    runtime: ResolverRuntime,
) {
    val groundKey = selection.groundKey()
    check(groundKey.field in world.resolverRegistry) {
        "Resolver26 resolveField received passive key $groundKey"
    }
    if (groundKey.arguments.argumentsContainErrorValue()) {
        cell.getValue().complete(ErrorEngineResult)
        cell.setAccessResult(ErrorEngineResult)
        return
    }
    val resolverArguments = groundKey.arguments as Arguments.Resolved

    val coordinate = path + groundKey
    val constructionDemand: SelectionForest = selection.subselections
    val invocationDemand: SelectionForest = constructionDemand.successorDemand()
    val input: EngineObjectData.Sync =
        target.materializeResolverInput(
            selections = expansion.inputMaterializeSelections,
            reader = coordinate,
            resultPath = path,
        )
    runtime.observeApplication(
        Resolver26ApplicationObservation(
            occurrencePath = coordinate,
            field = groundKey.field,
            input = input,
            arguments = resolverArguments,
            suppliedDemand = invocationDemand,
            variableArgumentCount = variableArgumentCount,
            occurrenceStamp =
                groundKey.stamp as? Stamp.Occurrence,
            variableSourceSelectionStamps = variableSourceSelectionStamps,
        ),
    )
    val fieldValue: EngineOutputData? =
        expansion.resolver(
            input = input,
            arguments = resolverArguments,
            selections = invocationDemand,
        )
    val resolvedValue: ResolvedValue =
        fieldValue.resolveValue(
            expectedType = groundKey.field.type,
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
    cell.getValue().complete(resolvedValue.engineResult)
    cell.setAccessResult(true)
}

private fun Arguments.Ground.bindingFor(argumentName: String): VariableBinding =
    when (this) {
        Arguments.Error -> VariableBinding.Error
        is Arguments.Resolved -> VariableBinding.of(fieldValues.getValue(argumentName))
    }

// Returns whether this occurrence is a top-level object or list element in one resolver output.
private fun ObjectResolution.isRootOfOutputAt(
    coordinate: List<PathComponent>,
): Boolean =
    path.size >= coordinate.size &&
        path.take(coordinate.size) == coordinate &&
        path.drop(coordinate.size).all { component -> component is ListEngineResult.Index }

// Starts orchestration for object values already materialized by resolveValue.
context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
private fun launchPassiveChildOrchestrations(
    path: List<PathComponent>,
    source: EngineOutputData?,
    target: EngineResult?,
    expectedType: TypeExpr<Schema.OutputTypeDef>,
    initialDemand: SelectionForest,
    runtime: ResolverRuntime,
) {
    when (source) {
        null -> {
            check(target == null) {
                "Resolver26 passive null source has non-null result at $path"
            }
        }

        EngineErrorData -> {
            check(target == ErrorEngineResult) {
                "Resolver26 passive error source has different result at $path"
            }
        }

        is Int,
        is Double,
        is String,
        is Boolean,
        -> {
            val simpleType =
                (expectedType as TypeExpr.Named).baseType as Schema.SimpleTypeDef
            check(target == source.toEngineResult(simpleType)) {
                "Resolver26 passive simple source has different result at $path"
            }
        }

        is EngineObjectData.Sync -> {
            check(target is ObjectEngineResult) {
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

        is List<*> -> {
            val listType = expectedType as TypeExpr.List
            check(target is ListEngineResult && target.size == source.size) {
                "Resolver26 passive list source has different result shape at $path"
            }
            source.forEachIndexed { index, value ->
                launchPassiveChildOrchestrations(
                    path = path + ListEngineResult.Index.of(index),
                    source = value,
                    target = target[index].getValue().get(),
                    expectedType = listType.elementType,
                    initialDemand = initialDemand,
                    runtime = runtime,
                )
            }
        }
        else -> error("Unsupported resolver output at $path: $source")
    }
}

private data class ResolverExpansion(
    val ownerKey: ObjectEngineResult.ObjectKey,
    val resolver: FieldResolver,
    val inputDemand: SelectionForest,
    val inputMaterializeSelections: MaterializeSelectionForest,
    val variableDefinitions: List<SelectionStampedVariableDefinition>,
)

private class CloseInputDemandResult(
    val demand: ObjectSelectionForest,
    val expansions: Map<ObjectEngineResult.ObjectKey, ResolverExpansion>,
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
    val sourceVariable: Arguments.Variable,
    val localizedVariable: Arguments.Variable,
)
