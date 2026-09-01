package semantics

import viaduct.graphql.schema.ViaductSchema

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.SelectionForest
import model.groundKey
import model.schemaType
import java.util.PriorityQueue
import viaduct.engine.api.EngineObjectData

/**
 * A single-threaded work queue that preserves the recursive resolver's depth-first traversal.
 *
 * Each instance constructs one result and [resolve] may be called exactly once.
 */
internal interface DepthFirstReactor {
    context(world: Assumptions, resolverSupport: ResolverSupport)
    fun resolve(): ObjectEngineResult

    sealed interface Task {
        val path: List<PathComponent>
    }

    class SlotOrchestrator(
        override val path: List<PathComponent>,
        val source: EngineObjectData.Sync,
        val selections: SelectionForest,
        val target: ObjectEngineResult,
    ) : Task

    class SlotResolver(
        override val path: List<PathComponent>,
        val source: EngineObjectData.Sync,
        val selection: ObjectSelection,
        val target: ObjectEngineResult,
    ) : Task

    companion object {
        context(world: Assumptions, resolverSupport: ResolverSupport)
        operator fun invoke(
            source: EngineObjectData.Sync,
            selections: SelectionForest,
            eventObserver: ReactorEventObserver = {},
        ): DepthFirstReactor {
            val reactor =
                PriorityQueueDepthFirstReactor(
                    schemaType = source.schemaType,
                    eventObserver = eventObserver,
                )
            reactor.initialize(
                source = source,
                selections = selections,
            )
            return reactor
        }
    }
}

private class PriorityQueueDepthFirstReactor(
    schemaType: ViaductSchema.Object,
    eventObserver: ReactorEventObserver,
) : DepthFirstReactor {
    private val result = ObjectEngineResult.of(schemaType, emptyMap(), mutable = true)
    private val tasks = PriorityQueue(depthFirstTaskComparator)
    private val instrumentation = ReactorInstrumentation(eventObserver)
    private var nextSequence = 0L
    private var started = false

    context(world: Assumptions)
    fun initialize(
        source: EngineObjectData.Sync,
        selections: SelectionForest,
    ) {
        enqueue(
            DepthFirstReactor.SlotOrchestrator(
                path = emptyList(),
                source = source,
                selections = selections,
                target = result,
            ),
        )
    }

    context(world: Assumptions, resolverSupport: ResolverSupport)
    override fun resolve(): ObjectEngineResult {
        check(!started) { "DepthFirstReactor.resolve() may only be called once" }
        started = true

        while (tasks.isNotEmpty()) {
            val task = tasks.remove().task
            when (task) {
                is DepthFirstReactor.SlotOrchestrator -> {
                    instrumentation.orchestratorStarted(task.path)
                    task.execute()
                }

                is DepthFirstReactor.SlotResolver -> {
                    instrumentation.resolverStarted(task.coordinate)
                    task.execute()
                }
            }
        }
        instrumentation.resolutionFinished()
        return result
    }

    context(world: Assumptions, resolverSupport: ResolverSupport)
    private fun DepthFirstReactor.SlotOrchestrator.execute() {
        require(target.type == source.schemaType) {
            "Initial result type ${target.type.name} does not match ${source.schemaType}"
        }

        val closedDemand = source.closeResolverDemand(path, selections)
        source.materializedChildOccurrences(path, closedDemand, target)
            .forEach { passiveObjectOccurrence ->
                enqueue(
                    DepthFirstReactor.SlotOrchestrator(
                        path = passiveObjectOccurrence.path,
                        source = passiveObjectOccurrence.source,
                        selections = passiveObjectOccurrence.selections,
                        target = passiveObjectOccurrence.target,
                    ),
                )
            }
        val unresolvedKeys = closedDemand.groundKeys() - target.requireGroundKeys()
        source.dependencyOrder(path, unresolvedKeys).forEach { key ->
            enqueue(
                DepthFirstReactor.SlotResolver(
                    path = path,
                    source = source,
                    selection = closedDemand[key],
                    target = target,
                ),
            )
        }
        instrumentation.orchestratorFinished(path, target, closedDemand)
    }

    context(world: Assumptions, resolverSupport: ResolverSupport)
    private fun DepthFirstReactor.SlotResolver.execute() {
        source
            .resolveKey(path, selection, target)
            ?.resolveRetainedObjects { passiveObjectOccurrence ->
                enqueue(
                    DepthFirstReactor.SlotOrchestrator(
                        path = passiveObjectOccurrence.path,
                        source = passiveObjectOccurrence.source,
                        selections = passiveObjectOccurrence.selections,
                        target = passiveObjectOccurrence.target,
                    ),
                )
            }
        instrumentation.resolverFinished(coordinate)
    }

    context(world: Assumptions)
    private fun enqueue(task: DepthFirstReactor.Task) {
        when (task) {
            is DepthFirstReactor.SlotOrchestrator ->
                instrumentation.orchestratorLaunched(
                    path = task.path,
                    objectType = task.source.schemaType.name,
                )

            is DepthFirstReactor.SlotResolver ->
                instrumentation.resolverLaunched(
                    coordinate = task.coordinate,
                    kind = task.selection.groundKey().reactorSlotKind(),
                )
        }
        tasks += ScheduledTask(task, nextSequence)
        nextSequence += 1
    }

    private val DepthFirstReactor.SlotResolver.coordinate: List<PathComponent>
        get() = path + selection.groundKey()
}

internal class ScheduledTask(
    val task: DepthFirstReactor.Task,
    val sequence: Long,
)

internal val depthFirstTaskComparator =
    compareByDescending<ScheduledTask> { scheduled -> scheduled.task.path.size }
        .thenBy { scheduled ->
            when (scheduled.task) {
                is DepthFirstReactor.SlotResolver -> 0
                is DepthFirstReactor.SlotOrchestrator -> 1
            }
        }
        .thenBy { scheduled -> scheduled.sequence }
