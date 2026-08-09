package semantics.resolver09

import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.Value
import model.groundKey
import semantics.ReactorEventObserver
import semantics.ReactorInstrumentation
import semantics.ReactorSlotKind
import semantics.SelectionCompleter
import semantics.closeResolverDemand
import semantics.reactorSlotKind
import semantics.resolverDependencies
import semantics.resolveKey

internal class IllegalResolverStateException(
    report: String,
) : IllegalStateException(report)

/**
 * A single-threaded readiness worklist over exact resolver occurrences.
 */
internal class Reactor private constructor(
    source: Value.Object,
    eventObserver: ReactorEventObserver = {},
) {
    private val result = EngineResult.Object.of(source.type, emptyMap(), mutable = true)
    private val slotResolverQueue = ArrayDeque<SlotResolver>()
    private val unfinishedOrchestrators = mutableListOf<SlotOrchestrator>()
    private val slotResolversByCoordinate =
        mutableMapOf<List<PathComponent>, SlotResolver>()
    private val instrumentation = ReactorInstrumentation(eventObserver)

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

            val iterator = unfinishedOrchestrators.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().launchResolvers()) {
                    iterator.remove()
                }
            }

            if (slotResolverQueue.isNotEmpty()) continue
            if (unfinishedOrchestrators.isEmpty()) {
                validateCompletion()
                return result
            }
            throw IllegalResolverStateException(illegalStateReport())
        }
    }

    context(world: Assumptions, selectionCompleter: SelectionCompleter)
    private fun launchOrchestrator(
        path: List<PathComponent>,
        source: Value.Object,
        selections: SelectionForest,
        target: EngineResult.Object,
    ) {
        require(target.type == source.type) {
            "Initial result type ${target.type.typeName} does not match ${source.type}"
        }
        val closedDemand = source.type.closeResolverDemand(path, selections)
        val slotResolvers =
            closedDemand
                .byGroundKey()
                .values
                .filter { selection -> !target.isValueSet(selection.groundKey()) }
                .map { selection ->
                    SlotResolver(
                        containingObjectPath = path,
                        source = source,
                        selection = selection,
                        target = target,
                    )
                }
        slotResolvers.forEach(::register)
        instrumentation.orchestratorLaunched(path, source.type.typeName)
        val orchestrator =
            SlotOrchestrator(
                path = path,
                target = target,
                closedDemand = closedDemand,
                slotResolvers = slotResolvers,
            )
        if (orchestrator.isFinished()) {
            instrumentation.orchestratorStarted(path)
            instrumentation.orchestratorFinished(path, target, closedDemand)
            return
        }
        unfinishedOrchestrators += orchestrator
    }

    private inner class SlotOrchestrator(
        val path: List<PathComponent>,
        val target: EngineResult.Object,
        val closedDemand: ObjectSelectionForest,
        slotResolvers: List<SlotResolver>,
    ) {
        private var started = false
        private val unfinished: MutableMap<SlotResolver, Set<SlotResolver>> =
            slotResolvers
                .associateWith { emptySet<SlotResolver>() }
                .toMutableMap()

        context(world: Assumptions, selectionCompleter: SelectionCompleter)
        fun launchResolvers(): Boolean {
            if (!started) {
                instrumentation.orchestratorStarted(path)
                started = true
            }
            refreshDependencies()
            unfinished.entries.toList().forEach { (slotResolver, dependencies) ->
                if (slotResolver.isReady(dependencies)) {
                    launch(slotResolver, dependencies)
                }
            }
            return isFinished().also { finished ->
                if (finished) {
                    instrumentation.orchestratorFinished(path, target, closedDemand)
                }
            }
        }

        context(world: Assumptions)
        private fun launch(
            slotResolver: SlotResolver,
            dependencies: Set<SlotResolver>,
        ) {
            unfinished.remove(slotResolver)
            slotResolver.markLaunched(dependencies)
            slotResolverQueue += slotResolver
        }

        context(world: Assumptions)
        private fun refreshDependencies() {
            val refreshed =
                target.resolverDependencies(
                    path = path,
                    selections = closedDemand,
                    completedResolverCoordinates =
                        slotResolversByCoordinate
                            .values
                            .filterTo(linkedSetOf()) { slotResolver ->
                                slotResolver.isFinished
                            }.mapTo(linkedSetOf(), SlotResolver::coordinate),
                )
            unfinished.keys.toList().forEach { slotResolver ->
                unfinished[slotResolver] =
                    refreshed
                        .getValue(slotResolver.key)
                        .mapTo(linkedSetOf()) { coordinate ->
                            slotResolversByCoordinate.getValue(coordinate)
                        }
            }
        }

        fun unfinishedSlots(): Map<SlotResolver, Set<SlotResolver>> =
            unfinished.toMap()

        fun isFinished(): Boolean = unfinished.isEmpty()
    }

    private inner class SlotResolver(
        val containingObjectPath: List<PathComponent>,
        val source: Value.Object,
        val selection: ObjectSelection,
        val target: EngineResult.Object,
    ) {
        var isFinished: Boolean = false
            private set

        val key: Value.GroundKey
            get() = selection.groundKey()

        val coordinate: List<PathComponent>
            get() = containingObjectPath + key

        context(world: Assumptions)
        fun isReady(dependencies: Set<SlotResolver>): Boolean =
            when (key.reactorSlotKind()) {
                ReactorSlotKind.ENGINE_OWNED -> true
                ReactorSlotKind.PASSIVE -> false
                ReactorSlotKind.FIELD_RESOLVER -> {
                    val absent =
                        dependencies
                            .filterNotTo(linkedSetOf(), SlotResolver::isFinished)
                            .mapTo(linkedSetOf(), SlotResolver::coordinate)
                    instrumentation.readinessEvaluated(
                        coordinate = coordinate,
                        requiredCoordinates =
                            dependencies.mapTo(
                                linkedSetOf(),
                                SlotResolver::coordinate,
                            ),
                        absentCoordinates = absent,
                    )
                    dependencies.all(SlotResolver::isFinished)
                }
            }

        context(world: Assumptions)
        fun markLaunched(dependencies: Set<SlotResolver>) {
            if (key.reactorSlotKind() == ReactorSlotKind.FIELD_RESOLVER) {
                instrumentation.resolverDependenciesApplied(
                    coordinate = coordinate,
                    dependencyCoordinates =
                        dependencies.mapTo(
                            linkedSetOf(),
                            SlotResolver::coordinate,
                        ),
                )
            }
            instrumentation.resolverLaunched(coordinate, key.reactorSlotKind())
        }

        context(world: Assumptions, selectionCompleter: SelectionCompleter)
        fun execute() {
            instrumentation.resolverStarted(coordinate)
            val resolvedValue =
                source.resolveKey(
                    path = containingObjectPath,
                    fieldSelection = selection,
                    resolved = target,
                )
            when (key.reactorSlotKind()) {
                ReactorSlotKind.ENGINE_OWNED -> {
                    check(resolvedValue == null) {
                        "Engine-owned slot unexpectedly produced a resolver fringe: $key"
                    }
                }

                ReactorSlotKind.FIELD_RESOLVER -> {
                    resolvedValue
                        ?.objectsNeedingResolution
                        ?.forEach { objectResolution ->
                            launchOrchestrator(
                                path = objectResolution.path,
                                source = objectResolution.source,
                                selections = objectResolution.selections,
                                target = objectResolution.target,
                            )
                        }
                }

                ReactorSlotKind.PASSIVE ->
                    error("A passive slot cannot be launched: $key")
            }
            check(!isFinished) {
                "Slot resolver finished more than once: ${coordinate.renderPath()}"
            }
            isFinished = true
            instrumentation.resolverFinished(coordinate)
        }

        context(world: Assumptions)
        fun readinessReport(
            dependencies: Set<SlotResolver>,
        ): ReadinessReport =
            when (key.reactorSlotKind()) {
                ReactorSlotKind.FIELD_RESOLVER -> {
                    val absent =
                        dependencies
                            .filterNotTo(linkedSetOf(), SlotResolver::isFinished)
                            .mapTo(linkedSetOf(), SlotResolver::coordinate)
                    ReadinessReport(
                        candidateCoordinate = coordinate,
                        requiredCoordinates =
                            dependencies.mapTo(
                                linkedSetOf(),
                                SlotResolver::coordinate,
                            ),
                        absentCoordinates = absent,
                        blocks = absent.structuralBlocks(),
                    )
                }

                ReactorSlotKind.ENGINE_OWNED ->
                    ReadinessReport(
                        candidateCoordinate = coordinate,
                        requiredCoordinates = emptySet(),
                        absentCoordinates = emptySet(),
                        blocks = emptyList(),
                    )
                ReactorSlotKind.PASSIVE ->
                    ReadinessReport(
                        candidateCoordinate = coordinate,
                        requiredCoordinates = emptySet(),
                        absentCoordinates = emptySet(),
                        blocks =
                            listOf(
                                ReadinessBlock(
                                    objectPath = containingObjectPath,
                                    missingCoordinate = coordinate,
                                    reason = "missing passive content",
                                ),
                            ),
                    )
            }

        context(world: Assumptions)
        private fun Set<List<PathComponent>>.structuralBlocks(): List<ReadinessBlock> =
            mapNotNull { missingCoordinate ->
                val missingKey = missingCoordinate.last() as Value.GroundKey
                if (missingKey.reactorSlotKind() == ReactorSlotKind.FIELD_RESOLVER) {
                    null
                } else {
                    ReadinessBlock(
                        objectPath = missingCoordinate.dropLast(1),
                        missingCoordinate = missingCoordinate,
                        reason = "missing passive or engine-owned content",
                    )
                }
            }
    }

    private fun validateCompletion() {
        check(slotResolverQueue.isEmpty())
        check(slotResolversByCoordinate.values.all(SlotResolver::isFinished)) {
            "Resolution completed with unfinished slot resolvers"
        }
        instrumentation.resolutionFinished()
    }

    context(world: Assumptions)
    private fun illegalStateReport(): String {
        val reports =
            unfinishedOrchestrators.flatMap { orchestrator ->
                orchestrator
                    .unfinishedSlots()
                    .map { (slotResolver, dependencies) ->
                        slotResolver.readinessReport(dependencies)
                    }
            }
        val unlaunched =
            unfinishedOrchestrators
                .flatMapTo(linkedSetOf()) { orchestrator ->
                    orchestrator
                        .unfinishedSlots()
                        .keys
                        .map(SlotResolver::coordinate)
                }
        val dependencyCycles = dependencyCycles(reports, unlaunched)
        val missingProducers =
            reports
                .flatMapTo(linkedSetOf()) { report -> report.absentCoordinates }
                .filterTo(linkedSetOf()) { coordinate -> coordinate !in unlaunched }
        val shape =
            when {
                dependencyCycles.isNotEmpty() -> "dependency cycle"
                missingProducers.isNotEmpty() ||
                    reports.any { report -> report.blocks.isNotEmpty() } ->
                    "missing producer structure"
                else -> "blocked readiness"
            }

        return buildString {
            appendLine("Illegal resolver state: readiness worklist quiesced with unfinished work.")
            appendLine("Observed shape: $shape")
            reports.forEach { report ->
                appendLine("Candidate: ${report.candidateCoordinate.renderPath()}")
                appendLine(
                    "  required: " +
                        report.requiredCoordinates.renderCoordinates(),
                )
                appendLine(
                    "  absent: " +
                        report.absentCoordinates.renderCoordinates(),
                )
                report.blocks.forEach { block ->
                    appendLine(
                        "  blocked at OER ${block.objectPath.renderPath()}: " +
                            "${block.reason}; missing=${block.missingCoordinate.renderPath()}",
                    )
                }
            }
            dependencyCycles.forEach { cycle ->
                appendLine(
                    "Dependency cycle: " +
                        cycle.joinToString(" -> ") { coordinate ->
                            coordinate.renderPath()
                        },
                )
            }
            if (missingProducers.isNotEmpty()) {
                appendLine("Missing producers: ${missingProducers.renderCoordinates()}")
            }
        }.trimEnd()
    }

    private fun register(slotResolver: SlotResolver) {
        check(
            slotResolversByCoordinate.putIfAbsent(
                slotResolver.coordinate,
                slotResolver,
            ) == null,
        ) {
            "Slot resolver created more than once: ${slotResolver.coordinate.renderPath()}"
        }
    }
}

private data class ReadinessReport(
    val candidateCoordinate: List<PathComponent>,
    val requiredCoordinates: Set<List<PathComponent>>,
    val absentCoordinates: Set<List<PathComponent>>,
    val blocks: List<ReadinessBlock>,
)

private data class ReadinessBlock(
    val objectPath: List<PathComponent>,
    val missingCoordinate: List<PathComponent>,
    val reason: String,
)

private fun dependencyCycles(
    reports: List<ReadinessReport>,
    candidates: Set<List<PathComponent>>,
): List<List<List<PathComponent>>> {
    val edges =
        reports.associate { report ->
            report.candidateCoordinate to
                report.absentCoordinates.filterTo(linkedSetOf()) { coordinate ->
                    coordinate in candidates
                }
        }
    val cycles = mutableListOf<List<List<PathComponent>>>()
    val visited = mutableSetOf<List<PathComponent>>()
    val stack = mutableListOf<List<PathComponent>>()

    fun visit(coordinate: List<PathComponent>) {
        val cycleStart = stack.indexOf(coordinate)
        if (cycleStart >= 0) {
            cycles += stack.drop(cycleStart) + listOf(coordinate)
            return
        }
        if (!visited.add(coordinate)) return
        stack += coordinate
        edges[coordinate].orEmpty().forEach(::visit)
        stack.removeAt(stack.lastIndex)
    }

    candidates.forEach(::visit)
    return cycles
}

private fun Iterable<List<PathComponent>>.renderCoordinates(): String {
    val coordinates = toList()
    return if (coordinates.isEmpty()) {
        "none"
    } else {
        coordinates.joinToString { coordinate -> coordinate.renderPath() }
    }
}

private fun List<PathComponent>.renderPath(): String =
    if (isEmpty()) {
        "<root>"
    } else {
        joinToString(separator = "/") { component ->
            when (component) {
                is Value.GroundKey ->
                    "${component.field.containingType.typeName}.${component.field.fieldName}" +
                        component.arguments.fieldValues.entries.joinToString(
                            prefix = "(",
                            postfix = ")",
                        ) { (name, value) -> "$name=$value" }
                is Value.ListIndex -> "[${component.index}]"
            }
        }
    }
