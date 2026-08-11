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
import model.PathComponent
import model.Promise
import model.Schema
import model.SelectionForest
import model.TypeExpr
import model.Value
import model.fetchBindings
import model.groundKey
import model.merge
import model.objectKey
import model.selectionForestOf
import model.usedVariables
import model.registry.VariableDefinition
import model.registry.successorDemand
import semantics.ResolvedValue
import semantics.RuntimeSupport
import semantics.bindFromArguments
import semantics.correctresolution.argumentsContainErrorValue
import semantics.materialize
import semantics.resolveValue

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
        orchestrator.start()
        return orchestrator.orchestrationReady
    }
}

/**
 * Resolves one object-result instance through per-field preparation latches.
 *
 * A field's demand is sealed only after every resolver field that can contribute a selection to
 * it has completed preparation and every direct provider needed by one of its open keys has
 * completed. Preparation grounds and merges all equal keys, prepares each resulting resolver
 * instance exactly once, and publishes the immutable selection map consumed during launch.
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

    // Adds normalized demand to the corresponding field states before preparation completes.
    fun addDemand(demand: SelectionForest) {
        demand.merge(source.type).byKey().values.forEach { selection ->
            fields.getValue(selection.key.field).add(selection)
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

        val grounded: List<ObjectSelection> =
            state.snapshot().map { selection ->
                val specialized = selection.objectKey(source.type)
                ObjectSelection.of(
                    key =
                        Value.GroundKey.of(
                            specialized.field,
                            specialized.arguments.fetchBindings(),
                        ),
                    possibleTypes = setOf(source.type),
                    subselections = selection.subselections,
                )
            }
        val sealedDemand: Map<Value.GroundKey, ObjectSelection> =
            grounded
                .groupBy(ObjectSelection::groundKey)
                .mapValues { (key, selections) ->
                    ObjectSelection.of(
                        key = key,
                        possibleTypes = setOf(source.type),
                        subselections =
                            selections.fold(selectionForestOf()) { demand, selection ->
                                demand + selection.subselections
                            },
                    )
                }

        sealedDemand.keys.forEach { key ->
            prepareResolverInstance(key)
        }
        state.sealedDemand.complete(sealedDemand)
    }

    /**
     * Prepares one exact resolver instance without launching its resolver.
     *
     * This operation:
     * - binds arg-variables;
     * - contributes the resolver's stamped object-fragment demand;
     * - declares path-variable bindings;
     * - launches path-variable fetchers.
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
        definitions.forEach { (variable, _) ->
            world.declareBinding(variable)
        }
        addDemand(resolver.stampedObjectFragment(coordinate))
        definitions.forEach { (variable, unresolvedRelativePathToVariableValue) ->
            runtime.scope.launch {
                val value =
                    readDirectProvider(
                        unresolvedRelativePathToVariableValue =
                            unresolvedRelativePathToVariableValue,
                        resolvedAbsolutePathToVariableReader = coordinate,
                    )
                world.completeBinding(variable, value)
            }
        }
    }

    // Grounds the relative path to the exact sibling field that provides this path-variable's
    // value, waits for that field's resolver, and returns its scalar result for binding. The cycle
    // check records the read against the consuming resolver instance's absolute path.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun readDirectProvider(
        unresolvedRelativePathToVariableValue: List<Value.Key>,
        resolvedAbsolutePathToVariableReader: List<PathComponent>,
    ): Value.Input? {
        val openKey = unresolvedRelativePathToVariableValue.single()
        val field = source.type.fields.getValue(openKey.field.fieldName)
        val keyOfVariableValue: Value.GroundKey =
            Value.GroundKey.of(
                field = field,
                arguments = openKey.arguments.fetchBindings(),
            )
        fields.getValue(keyOfVariableValue.field).promisesInstalled.await()
        diagnosticInstrumentation.cycleCheck(
            resolvedAbsolutePathToVariableReader,
            target,
            keyOfVariableValue,
        )
        return target.getValue(keyOfVariableValue).await().toProviderInput()
    }

    // Eagerly installs promises so the orchestration-ready latch can be released, waits until
    // resolver-input promises can be looked up, then launches one coroutine per unresolved key.
    context(world: Assumptions, diagnosticInstrumentation: RuntimeSupport)
    private suspend fun launchResolverInstances(state: FieldState) {
        val demand = state.sealedDemand.await()
        val unresolved: List<Value.GroundKey> =
            demand.keys.filter { key ->
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
                        selection = demand.getValue(key),
                        valuePromise = target.getValue(key),
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
    ) {
        val key = selection.groundKey()
        when {
            key.arguments.argumentsContainErrorValue() -> valuePromise.complete(Value.Error)
            key.field.fieldName == "__typename" ->
                valuePromise.complete(Value.String.of(source.type.typeName))
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
                valuePromise.complete(resolvedValue.engineResult)
            }
        }
    }

    private class FieldState(
        val field: Schema.ObjectField,
    ) {
        private val selections: MutableList<ObjectSelection> = mutableListOf()

        // Carries the immutable demand and acts as the preparation latch: completion means demand
        // is sealed and every exact resolver instance for this field has been prepared.
        val sealedDemand:
            CompletableDeferred<Map<Value.GroundKey, ObjectSelection>> =
            CompletableDeferred()

        // Opens once every demanded value promise exists.
        val promisesInstalled: CompletableDeferred<Unit> = CompletableDeferred()

        // Records one normalized selection while this field's demand is still open.
        @Synchronized
        fun add(selection: ObjectSelection) {
            check(!sealedDemand.isCompleted) {
                "Demand arrived after ${field.containingType.typeName}/${field.fieldName} sealed"
            }
            selections += selection
        }

        // Returns a stable view of the selections accumulated before sealing.
        @Synchronized
        fun snapshot(): List<ObjectSelection> = selections.toList()
    }
}

private object StrictPreparationPlan {
    class TypePlan(
        private val demandContributorsByField:
            Map<Schema.ObjectField, Set<Schema.ObjectField>>,
        private val incomingPathVarProvidersByField:
            Map<Schema.ObjectField, Set<Schema.ObjectField>>,
        private val resolverInputFieldsByField:
            Map<Schema.ObjectField, Set<Schema.ObjectField>>,
    ) {
        // Returns fields whose preparation can contribute additional demand to this field.
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
                edge(
                    FieldStep(field, Phase.PREPARE),
                    FieldStep(required, Phase.PREPARE),
                )
                edge(
                    FieldStep(required, Phase.LAUNCH),
                    FieldStep(field, Phase.LAUNCH),
                )
            }
            resolver.variables.forEach { (variable, definition) ->
                if (definition !is VariableDefinition.FromObjectField) return@forEach
                validateStrictVariable(world, resolver.field, variable, definition)
                val provider: Schema.ObjectField =
                    type.fields.getValue(
                        definition.path.single().field.fieldName,
                    )
                directUseFields(resolver.objectFragment, variable).forEach { useField ->
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

    // Enforces Resolver25's deliberately narrow path-variable shape before adding its provider
    // dependency to the phase graph.
    private fun validateStrictVariable(
        world: Assumptions,
        definingField: Schema.ObjectField,
        variable: Value.Variable.Template,
        definition: VariableDefinition.FromObjectField,
    ) {
        require(definition.path.size == 1) {
            "Resolver25 requires a direct sibling provider for " +
                "${definingField.coordinate()} variable \$${variable.variableName}"
        }
        val providerKey = definition.path.single()
        val provider: Schema.ObjectField =
            definingField.containingType.fields.getValue(
                providerKey.field.fieldName,
            )
        require(
            provider.containingType == definingField.containingType &&
                provider.typeExpr is TypeExpr.Named &&
                provider.typeExpr.baseType is Schema.SimpleType,
        ) {
            "Resolver25 requires a direct scalar sibling provider for " +
                "${definingField.coordinate()} variable \$${variable.variableName}"
        }
        require(providerKey.arguments.usedVariables().isEmpty()) {
            "Resolver25 provider keys cannot contain variables: ${provider.coordinate()}"
        }
        require(provider in world.resolverRegistry) {
            "Resolver25 requires an active provider resolver: ${provider.coordinate()}"
        }
        require(
            world.resolverRegistry
                .resolver(provider)
                .variables
                .values
                .none { it is VariableDefinition.FromObjectField },
        ) {
            "Resolver25 does not support path-variable provider chains at " +
                provider.coordinate()
        }

        val directUses = directUseFields(
            world.resolverRegistry.resolver(definingField).objectFragment,
            variable,
        )
        require(directUses.all { it in world.resolverRegistry }) {
            "Resolver25 path variables may only occur in direct sibling resolver keys"
        }
        require(
            world.resolverRegistry
                .resolver(definingField)
                .objectFragment
                .all { selection ->
                    variable !in selection.subselections.usedVariables()
                },
        ) {
            "Resolver25 path variables may not occur below a direct sibling selection"
        }
    }

    // Finds the direct sibling fields whose key arguments consume this path variable.
    private fun directUseFields(
        fragment: SelectionForest,
        variable: Value.Variable.Template,
    ): Set<Schema.ObjectField> {
        val uses = linkedSetOf<Schema.ObjectField>()
        fragment.merge(variable.field.containingType).byKey().values.forEach { selection ->
            if (variable in selection.key.arguments.usedVariables()) {
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

// Converts the nullable scalar result of a direct provider into a path-variable input.
private fun EngineResult?.toProviderInput(): Value.Input? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this
        is EngineResult.List ->
            error("Resolver25 requires a direct scalar provider")
        is EngineResult.Object ->
            error("Resolver25 requires a direct scalar provider")
    }

// Renders a canonical object-field coordinate for validation diagnostics.
private fun Schema.ObjectField.coordinate(): String =
    "${containingType.typeName}/$fieldName"
