package semantics.resolver10

import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.Schema
import model.Selection
import model.SelectionForest
import model.TypeExpr
import model.Value
import model.groundKey
import model.instantiateBindings
import model.merge
import model.objectKey
import model.selectionForestOf
import model.stampedVariables
import model.usedVariables
import model.registry.StampedObjectPathDefinition
import semantics.ReactorEventObserver
import semantics.ReactorInstrumentation
import semantics.ReactorSlotKind
import semantics.SelectionCompleter
import semantics.bindFromArguments
import semantics.reactorSlotKind
import semantics.renderReactorPath
import semantics.resolverDependencies
import semantics.resolveKey
import semantics.resolveValue

internal class IllegalResolverStateException(
    report: String,
) : IllegalStateException(report)

/**
 * A single-threaded readiness worklist over symbolic demand and exact resolver occurrences.
 */
internal class Reactor private constructor(
    source: Value.Object,
    eventObserver: ReactorEventObserver = {},
) {
    private val result = EngineResult.Object.of(source.type, emptyMap(), mutable = true)
    private val slotResolverQueue = ArrayDeque<SlotResolver>()
    private val unfinishedOrchestrators = linkedSetOf<SlotOrchestrator>()
    private val orchestratorsByPath =
        mutableMapOf<List<PathComponent>, SlotOrchestrator>()
    private val slotResolversByCoordinate =
        mutableMapOf<List<PathComponent>, SlotResolver>()
    private val instrumentation = ReactorInstrumentation(eventObserver)
    private var progressVersion = 0L

    companion object {
        context(world: Assumptions, selectionCompleter: SelectionCompleter)
        operator fun invoke(
            source: Value.Object,
            selections: SelectionForest,
            eventObserver: ReactorEventObserver = {},
        ): EngineResult.Object {
            val reactor = Reactor(source, eventObserver)
            reactor.launchOrchestrator(
                path = emptyList(),
                source = source,
                selections = selections,
                target = reactor.result,
            )
            return reactor.run()
        }
    }

    context(world: Assumptions, selectionCompleter: SelectionCompleter)
    private fun run(): EngineResult.Object {
        while (true) {
            while (slotResolverQueue.isNotEmpty()) {
                slotResolverQueue.removeFirst().execute()
            }

            val before = progressVersion
            unfinishedOrchestrators.toList().forEach { orchestrator ->
                if (orchestrator.launchResolvers()) {
                    unfinishedOrchestrators.remove(orchestrator)
                }
            }

            if (slotResolverQueue.isNotEmpty()) continue
            if (progressVersion != before) continue
            if (
                unfinishedOrchestrators.isEmpty() &&
                slotResolversByCoordinate.values.all(SlotResolver::isFinished)
            ) {
                orchestratorsByPath.values.forEach(SlotOrchestrator::finish)
                validateCompletion()
                return result
            }
            throw IllegalResolverStateException(illegalStateReport())
        }
    }

    context(world: Assumptions)
    private fun launchOrchestrator(
        path: List<PathComponent>,
        source: Value.Object,
        selections: SelectionForest,
        target: EngineResult.Object,
    ) {
        require(target.type == source.type) {
            "Initial result type ${target.type.typeName} does not match ${source.type}"
        }
        val envelope = source.type.projectionEnvelope(selections)
        val orchestrator =
            SlotOrchestrator(
                path = path,
                source = source,
                target = target,
                initialDemand = selections,
                projectionEnvelope = envelope,
            )
        check(orchestratorsByPath.putIfAbsent(path, orchestrator) == null) {
            "Orchestrator created more than once: ${path.renderReactorPath()}"
        }
        instrumentation.orchestratorLaunched(path, source.type.typeName)
        unfinishedOrchestrators += orchestrator
    }

    private inner class SlotOrchestrator(
        val path: List<PathComponent>,
        val source: Value.Object,
        val target: EngineResult.Object,
        initialDemand: SelectionForest,
        private val projectionEnvelope: ObjectSelectionForest,
    ) {
        private var started = false
        private var finished = false
        private val pendingSelections = mutableListOf<ObjectSelection>()
        private val exactSelections =
            linkedMapOf<Value.GroundKey, ObjectSelection>()
        private val expanded = linkedSetOf<List<PathComponent>>()
        private val pendingBindings = mutableListOf<PendingBinding>()
        private val unfinished =
            linkedMapOf<SlotResolver, Set<SlotResolver>>()
        private val missingDependencies =
            linkedMapOf<SlotResolver, Set<List<PathComponent>>>()

        init {
            addDemand(initialDemand)
        }

        context(world: Assumptions, selectionCompleter: SelectionCompleter)
        fun launchResolvers(): Boolean {
            check(!finished)
            if (!started) {
                instrumentation.orchestratorStarted(path)
                started = true
            }

            var localProgress: Boolean
            do {
                localProgress = groundPendingSelections()
                localProgress = bindReadyProviders() || localProgress
            } while (localProgress)

            unfinished.keys
                .filterNot(SlotResolver::sealed)
                .forEach { slot ->
                slot.seal(selectionForLaunch(slot))
                instrumentation.slotSealed(slot.coordinate)
                progressed()
            }
            refreshDependencies()
            unfinished.entries.toList().forEach { (slot, dependencies) ->
                if (!slot.sealed) return@forEach
                val missing = missingDependencies[slot].orEmpty()
                if (
                    slot.isReady(dependencies, missing) &&
                    ownerBindingsComplete(slot.coordinate)
                ) {
                    unfinished.remove(slot)
                    missingDependencies.remove(slot)
                    slot.markLaunched(dependencies)
                    slotResolverQueue += slot
                    progressed()
                }
            }

            return isLocallyFinished()
        }

        fun finish() {
            check(started && !finished && isLocallyFinished())
            finished = true
            instrumentation.orchestratorFinished(path, target, closedDemand())
        }

        private fun isLocallyFinished(): Boolean =
                pendingSelections.isEmpty() &&
                    pendingBindings.isEmpty() &&
                    unfinished.isEmpty()

        fun addDemand(demand: SelectionForest) {
            check(!finished)
            unfinishedOrchestrators += this
            demand.merge(source.type).forEach { selection ->
                pendingSelections += selection as ObjectSelection
            }
            progressed()
        }

        context(world: Assumptions, selectionCompleter: SelectionCompleter)
        private fun groundPendingSelections(): Boolean {
            val ready =
                pendingSelections.filter { selection ->
                val variables = selection.key.stampedVariables()
                    selection.key.arguments.usedVariables().none {
                        it is Value.Variable.Template
                    } &&
                        variables.all(world::isBound)
                }
            ready.forEach { selection ->
                check(pendingSelections.remove(selection))
                val grounded =
                    selectionForestOf(selection)
                        .merge(source.type)
                        .instantiateBindings()
                        .single() as ObjectSelection
                discover(grounded)
                progressed()
            }
            return ready.isNotEmpty()
        }

        context(world: Assumptions, selectionCompleter: SelectionCompleter)
        private fun discover(selection: ObjectSelection) {
            val key = selection.groundKey()
            val previous = exactSelections[key]
            exactSelections[key] =
                if (previous == null) {
                    selection
                } else {
                    ObjectSelection.of(
                        key = key,
                        possibleTypes = setOf(source.type),
                        subselections = previous.subselections + selection.subselections,
                    )
                }

            if (!target.isSet(key) && slotResolversByCoordinate[path + key] == null) {
                if (key.reactorSlotKind() == ReactorSlotKind.PASSIVE) {
                    publishPassive(selection)
                } else {
                    val slot =
                        SlotResolver(
                            owner = this,
                            containingObjectPath = path,
                            source = source,
                            initialSelection = selection,
                            target = target,
                        )
                    register(slot)
                    unfinished[slot] = emptySet()
                    missingDependencies[slot] = emptySet()
                }
            }

            val coordinate = path + key
            if (target.isSet(key) && !selection.subselections.isEmpty()) {
                propagateDemand(
                    path = coordinate,
                    value = target.fetch(key).value,
                    demand = selection.subselections,
                )
            }
            if (
                key.reactorSlotKind() == ReactorSlotKind.FIELD_RESOLVER &&
                expanded.add(coordinate)
            ) {
                instrumentation.resolverOccurrenceExpanded(coordinate)
                listOf(key).bindFromArguments(path)
                val resolver = world.resolverRegistry.resolver(key.field)
                resolver
                    .stampedObjectPathDefinitions(coordinate)
                    .forEach { definition ->
                        pendingBindings +=
                            PendingBinding(
                                ownerCoordinate = coordinate,
                                definition = definition,
                            )
                    }
                addDemand(resolver.stampedObjectFragment(coordinate))
                progressed()
            }
        }

        context(world: Assumptions, selectionCompleter: SelectionCompleter)
        private fun publishPassive(selection: ObjectSelection) {
            val key = selection.groundKey()
            val completion = selectionCompleter.complete(selection.subselections)
            val resolvedValue =
                source.fieldValues
                    .getValue(key)
                    .resolveValue(
                        path = path + key,
                        resolverDemand = completion.selections,
                        beSelective =
                            completion.selective &&
                                !completion.retainCompleteOutput,
                    )
            target.write(key, EngineResult.Cell.of(resolvedValue.engineResult))
            resolvedValue.objectOccurrences.forEach { objectResolution ->
                launchOrchestrator(
                    path = objectResolution.path,
                    source = objectResolution.source,
                    selections = objectResolution.selections,
                    target = objectResolution.target,
                )
            }
            progressed()
        }

        context(world: Assumptions)
        private fun bindReadyProviders(): Boolean {
            var madeProgress = false
            val iterator = pendingBindings.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                when (val read = readProvider(pending)) {
                    ProviderRead.NotReady -> Unit
                    is ProviderRead.Ready -> {
                        check(!world.isBound(pending.definition.variable)) {
                            "Provider variable bound before its pending transition: " +
                                pending.definition.variable
                        }
                        world.bind(pending.definition.variable, read.value)
                        instrumentation.objectPathVariableBound(
                            ownerCoordinate = pending.ownerCoordinate,
                            variable = pending.definition.variable,
                            providerPath = pending.definition.path,
                            value = read.value,
                        )
                        iterator.remove()
                        madeProgress = true
                        progressed()
                    }
                }
            }
            return madeProgress
        }

        context(world: Assumptions)
        private fun readProvider(pending: PendingBinding): ProviderRead {
            var currentObject = target
            var currentPath = path
            pending.definition.path.forEachIndexed { index, openKey ->
                val specialized =
                    Selection.of(
                        key = openKey,
                        possibleTypes = setOf(currentObject.type),
                        subselections = selectionForestOf(),
                    ).objectKey(currentObject.type)
                if (
                    specialized.arguments.usedVariables().any {
                        it is Value.Variable.Template
                    } ||
                    specialized.stampedVariables().any { variable -> !world.isBound(variable) }
                ) {
                    return ProviderRead.NotReady
                }
                val selection =
                    ObjectSelection.of(
                        key = specialized,
                        possibleTypes = setOf(currentObject.type),
                        subselections = selectionForestOf(),
                    )
                val key =
                    ObjectSelectionForest.of(currentObject.type, listOf(selection))
                        .instantiateBindings()
                        .groundKeys()
                        .single()
                val coordinate = currentPath + key
                when (key.reactorSlotKind()) {
                    ReactorSlotKind.FIELD_RESOLVER -> {
                        val producer =
                            slotResolversByCoordinate[coordinate]
                                ?: return ProviderRead.NotReady
                        if (!producer.isFinished) return ProviderRead.NotReady
                    }
                    ReactorSlotKind.ENGINE_OWNED,
                    ReactorSlotKind.PASSIVE,
                    -> if (!currentObject.isSet(key)) return ProviderRead.NotReady
                }
                if (!currentObject.isSet(key)) return ProviderRead.NotReady
                val value = currentObject.fetch(key).value
                if (value == null) return ProviderRead.Ready(null)
                if (value == Value.Error) return ProviderRead.Ready(Value.Error)
                if (index == pending.definition.path.lastIndex) {
                    return ProviderRead.Ready(value.toProviderInput())
                }
                currentObject =
                    value as? EngineResult.Object
                        ?: error(
                            "Provider path crossed a non-object at " +
                                coordinate.renderReactorPath(),
                        )
                currentPath = coordinate
            }
            error("Provider path must be nonempty")
        }

        context(world: Assumptions)
        private fun selectionForLaunch(slot: SlotResolver): ObjectSelection {
            val exact = exactSelections.getValue(slot.key)
            val conservative =
                projectionEnvelope
                    .byKey()
                    .values
                    .filter { selection -> selection.mayContributeTo(slot.key) }
                    .fold(selectionForestOf()) { demand, selection ->
                        demand + selection.subselections.variableFreeProjectionSkeleton()
                    }
            return ObjectSelection.of(
                key = slot.key,
                possibleTypes = setOf(source.type),
                subselections =
                    (
                        exact.subselections.variableFreeProjectionSkeleton() +
                            conservative
                    )
                        .coalesceEquivalentSelections(),
            )
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
            return ObjectSelectionForest.of(source.type, listOf(this))
                .instantiateBindings()
                .groundKeys()
                .single() == targetKey
        }

        context(world: Assumptions)
        private fun refreshDependencies() {
            val completedCoordinates =
                slotResolversByCoordinate
                    .values
                    .filterTo(linkedSetOf(), SlotResolver::isFinished)
                    .mapTo(linkedSetOf(), SlotResolver::coordinate)
            unfinished.keys.toList().forEach { slot ->
                if (!slot.inputVariablesBound()) {
                    unfinished[slot] = emptySet()
                    return@forEach
                }
                val demand =
                    ObjectSelectionForest.of(
                        source.type,
                        listOf(exactSelections.getValue(slot.key)),
                    )
                val coordinates =
                    target
                        .resolverDependencies(
                            path = path,
                            selections = demand,
                            completedResolverCoordinates = completedCoordinates,
                        ).getValue(slot.key)
                val known = linkedSetOf<SlotResolver>()
                val missing = linkedSetOf<List<PathComponent>>()
                coordinates.forEach { coordinate ->
                    val dependency = slotResolversByCoordinate[coordinate]
                    if (dependency == null) {
                        missing += coordinate
                    } else {
                        known += dependency
                    }
                }
                unfinished[slot] = known
                missingDependencies[slot] = missing
            }
        }

        private fun ownerBindingsComplete(coordinate: List<PathComponent>): Boolean =
            pendingBindings.none { pending -> pending.ownerCoordinate == coordinate }

        private fun closedDemand(): ObjectSelectionForest =
            ObjectSelectionForest.of(source.type, exactSelections.values)

        fun propagatePublishedDemand(key: Value.GroundKey) {
            val selection = exactSelections.getValue(key)
            if (!selection.subselections.isEmpty()) {
                propagateDemand(
                    path = path + key,
                    value = target.fetch(key).value,
                    demand = selection.subselections,
                )
            }
        }

        context(world: Assumptions)
        fun report(): String =
            buildString {
                appendLine("OER: ${path.renderReactorPath()}")
                if (pendingSelections.isNotEmpty()) {
                    appendLine("  symbolic selections: ${pendingSelections.size}")
                    pendingSelections.forEach { selection ->
                        appendLine(
                            "    ${selection.key.field.fieldName}: unbound=" +
                                selection.key.stampedVariables().joinToString(),
                        )
                    }
                }
                pendingBindings.forEach { pending ->
                    appendLine(
                        "  provider ${pending.definition.variable} owned by " +
                            pending.ownerCoordinate.renderReactorPath(),
                    )
                }
                unfinished.forEach { (slot, dependencies) ->
                    appendLine(
                        "  slot ${slot.coordinate.renderReactorPath()}: " +
                            "sealed=${slot.sealed}, launched=${slot.launched}, " +
                            "kind=${slot.key.reactorSlotKind()}, " +
                            "inputsBound=${slot.inputVariablesBound()}, " +
                            "ownerBindingsComplete=" +
                            ownerBindingsComplete(slot.coordinate) +
                            ", " +
                            "dependencies=" +
                            dependencies.joinToString { dependency ->
                                "${dependency.coordinate.renderReactorPath()}" +
                                    "(finished=${dependency.isFinished})"
                            },
                    )
                    val missing = missingDependencies[slot].orEmpty()
                    if (missing.isNotEmpty()) {
                        appendLine(
                            "    missing=" +
                                missing.joinToString { it.renderReactorPath() },
                        )
                    }
                }
            }.trimEnd()
    }

    private inner class SlotResolver(
        val owner: SlotOrchestrator,
        val containingObjectPath: List<PathComponent>,
        val source: Value.Object,
        initialSelection: ObjectSelection,
        val target: EngineResult.Object,
    ) {
        private var launchSelection: ObjectSelection? = null
        var sealed: Boolean = false
            private set
        var launched: Boolean = false
            private set
        var isFinished: Boolean = false
            private set

        val key: Value.GroundKey = initialSelection.groundKey()
        val coordinate: List<PathComponent> = containingObjectPath + key

        fun seal(selection: ObjectSelection) {
            check(!sealed && !launched)
            check(selection.groundKey() == key)
            launchSelection = selection
            sealed = true
        }

        context(world: Assumptions)
        fun inputVariablesBound(): Boolean =
            key.reactorSlotKind() != ReactorSlotKind.FIELD_RESOLVER ||
                world.resolverRegistry
                    .resolver(key.field)
                    .stampedObjectFragment(coordinate)
                    .stampedVariables()
                    .all(world::isBound)

        context(world: Assumptions)
        fun isReady(
            dependencies: Set<SlotResolver>,
            missingDependencies: Set<List<PathComponent>>,
        ): Boolean {
            return when (key.reactorSlotKind()) {
                ReactorSlotKind.ENGINE_OWNED -> true
                ReactorSlotKind.PASSIVE -> false
                ReactorSlotKind.FIELD_RESOLVER -> {
                    if (!inputVariablesBound()) return false
                    val absent =
                        dependencies
                            .filterNotTo(linkedSetOf(), SlotResolver::isFinished)
                            .mapTo(linkedSetOf(), SlotResolver::coordinate) +
                            missingDependencies
                    instrumentation.readinessEvaluated(
                        coordinate = coordinate,
                        requiredCoordinates =
                            dependencies.mapTo(linkedSetOf(), SlotResolver::coordinate),
                        absentCoordinates = absent,
                    )
                    missingDependencies.isEmpty() &&
                        dependencies.all(SlotResolver::isFinished)
                }
            }
        }

        context(world: Assumptions)
        fun markLaunched(dependencies: Set<SlotResolver>) {
            check(sealed && !launched)
            launched = true
            if (key.reactorSlotKind() == ReactorSlotKind.FIELD_RESOLVER) {
                instrumentation.resolverDependenciesApplied(
                    coordinate = coordinate,
                    dependencyCoordinates =
                        dependencies.mapTo(linkedSetOf(), SlotResolver::coordinate),
                )
            }
            instrumentation.resolverLaunched(coordinate, key.reactorSlotKind())
        }

        context(world: Assumptions, selectionCompleter: SelectionCompleter)
        fun execute() {
            check(launched && !isFinished)
            instrumentation.resolverStarted(coordinate)
            val resolvedValue =
                source.resolveKey(
                    path = containingObjectPath,
                    fieldSelection = checkNotNull(launchSelection),
                    resolved = target,
                )
            when (key.reactorSlotKind()) {
                ReactorSlotKind.ENGINE_OWNED ->
                    check(resolvedValue == null) {
                        "Engine-owned slot unexpectedly produced a resolver fringe: $key"
                    }
                ReactorSlotKind.FIELD_RESOLVER ->
                    resolvedValue
                        ?.objectOccurrences
                        ?.forEach { objectResolution ->
                            launchOrchestrator(
                                path = objectResolution.path,
                                source = objectResolution.source,
                                selections = objectResolution.selections,
                                target = objectResolution.target,
                            )
                        }
                ReactorSlotKind.PASSIVE ->
                    error("A passive slot cannot be launched: $key")
            }
            owner.propagatePublishedDemand(key)
            isFinished = true
            instrumentation.resolverFinished(coordinate)
            progressed()
        }
    }

    private fun register(slotResolver: SlotResolver) {
        check(
            slotResolversByCoordinate.putIfAbsent(
                slotResolver.coordinate,
                slotResolver,
            ) == null,
        ) {
            "Slot resolver created more than once: " +
                slotResolver.coordinate.renderReactorPath()
        }
        instrumentation.slotRegistered(slotResolver.coordinate)
        progressed()
    }

    private fun propagateDemand(
        path: List<PathComponent>,
        value: EngineResult?,
        demand: SelectionForest,
    ) {
        when (value) {
            null,
            Value.Error,
            is Value.Simple,
            -> Unit

            is EngineResult.Object ->
                orchestratorsByPath
                    .getValue(path)
                    .addDemand(demand)

            is EngineResult.List ->
                value.forEachIndexed { index, cell ->
                    propagateDemand(
                        path = path + Value.ListIndex.of(index),
                        value = cell.value,
                        demand = demand,
                    )
                }
        }
    }

    private fun progressed() {
        progressVersion += 1
    }

    private fun validateCompletion() {
        check(slotResolverQueue.isEmpty())
        check(slotResolversByCoordinate.values.all(SlotResolver::isFinished)) {
            "Resolution completed with unfinished slot resolvers"
        }
        instrumentation.resolutionFinished()
    }

    context(world: Assumptions)
    private fun illegalStateReport(): String =
        buildString {
            appendLine(
                "Illegal resolver state: path-variable worklist quiesced with unfinished work.",
            )
            unfinishedOrchestrators.forEach { orchestrator ->
                appendLine(orchestrator.report())
            }
        }.trimEnd()
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
            newlyActivated.fold(demand) { current, field ->
                current + world.resolverRegistry.resolver(field).objectFragment
            }
    }
}

private data class PendingBinding(
    val ownerCoordinate: List<PathComponent>,
    val definition: StampedObjectPathDefinition,
)

private sealed interface ProviderRead {
    data object NotReady : ProviderRead

    data class Ready(
        val value: Value.Input?,
    ) : ProviderRead
}

private data class OpenSelectionCoordinate(
    val key: Value.Key,
    val possibleTypes: Set<Schema.ObjectType>,
)

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
    return childrenByCoordinate.entries.fold(selectionForestOf()) { result, entry ->
        val coordinate = entry.key
        val childForests = entry.value
        val children =
            childForests
                .fold(selectionForestOf(), SelectionForest::plus)
                .coalesceEquivalentSelections()
        result +
            selectionForestOf(
                Selection.of(
                    key = coordinate.key,
                    possibleTypes = coordinate.possibleTypes,
                    subselections = children,
                ),
            )
    }
}

/**
 * Retains ground projection work and the passive paths containing future variable-key boundaries.
 */
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
                cell.value?.toProviderInput()
            },
    )
}
