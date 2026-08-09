package semantics

import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.PathComponent
import model.SelectionForest
import model.Value
import java.util.PriorityQueue

/** Receives each task immediately before it executes. */
internal typealias DepthFirstTaskObserver = (DepthFirstReactor.Task) -> Unit

/**
 * A single-threaded work queue that preserves the recursive resolver's depth-first traversal.
 *
 * Each instance constructs one result and [resolve] may be called exactly once.
 */
internal interface DepthFirstReactor {
    fun resolve(): EngineResult.Object

    sealed interface Task {
        val path: List<PathComponent>
    }

    class SlotOrchestrator(
        override val path: List<PathComponent>,
        val source: Value.Object,
        val selections: SelectionForest,
        val target: EngineResult.Object,
    ) : Task

    class SlotResolver(
        override val path: List<PathComponent>,
        val source: Value.Object,
        val selection: ObjectSelection,
        val target: EngineResult.Object,
    ) : Task

    companion object {
        context(world: Assumptions, selectionCompleter: SelectionCompleter)
        operator fun invoke(
            source: Value.Object,
            selections: SelectionForest,
            taskObserver: DepthFirstTaskObserver = {},
        ): DepthFirstReactor =
            PriorityQueueDepthFirstReactor(
                world = world,
                selectionCompleter = selectionCompleter,
                source = source,
                selections = selections,
                taskObserver = taskObserver,
            )
    }
}

private class PriorityQueueDepthFirstReactor(
    private val world: Assumptions,
    private val selectionCompleter: SelectionCompleter,
    source: Value.Object,
    selections: SelectionForest,
    private val taskObserver: DepthFirstTaskObserver,
) : DepthFirstReactor {
    private val result = EngineResult.Object.of(source.type, emptyMap(), mutable = true)
    private val tasks = PriorityQueue(depthFirstTaskComparator)
    private var nextSequence = 0L
    private var started = false

    init {
        enqueue(
            DepthFirstReactor.SlotOrchestrator(
                path = emptyList(),
                source = source,
                selections = selections,
                target = result,
            ),
        )
    }

    override fun resolve(): EngineResult.Object {
        check(!started) { "DepthFirstReactor.resolve() may only be called once" }
        started = true

        context(world, selectionCompleter) {
            while (tasks.isNotEmpty()) {
                val task = tasks.remove().task
                taskObserver(task)
                when (task) {
                    is DepthFirstReactor.SlotOrchestrator -> task.execute()
                    is DepthFirstReactor.SlotResolver -> task.execute()
                }
            }
        }
        return result
    }

    context(world: Assumptions, selectionCompleter: SelectionCompleter)
    private fun DepthFirstReactor.SlotOrchestrator.execute() {
        require(target.type == source.type) {
            "Initial result type ${target.type.typeName} does not match ${source.type}"
        }

        val closedDemand = source.type.closeResolverDemand(path, selections)
        val unresolvedKeys = closedDemand.groundKeys() - target.keys
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
    }

    context(world: Assumptions, selectionCompleter: SelectionCompleter)
    private fun DepthFirstReactor.SlotResolver.execute() {
        source
            .resolveKey(path, selection, target)
            ?.resolveObjects { objectResolution ->
                enqueue(
                    DepthFirstReactor.SlotOrchestrator(
                        path = objectResolution.path,
                        source = objectResolution.source,
                        selections = objectResolution.selections,
                        target = objectResolution.target,
                    ),
                )
            }
    }

    private fun enqueue(task: DepthFirstReactor.Task) {
        tasks += ScheduledTask(task, nextSequence)
        nextSequence += 1
    }
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
