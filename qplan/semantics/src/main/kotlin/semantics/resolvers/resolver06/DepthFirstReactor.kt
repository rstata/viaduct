package semantics.resolvers.resolver06

import model.Arguments
import model.Assumptions
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.groundKey
import model.schemaType
import java.util.PriorityQueue
import semantics.resolvers.closeResolverDemand
import semantics.resolvers.materializedChildOccurrences
import semantics.resolvers.resolveRetainedObjects
import semantics.resolvers.resolver01.DepthFirstResolve
import semantics.resolvers.resolver01.requireGroundKeys
import semantics.shared.OperationContext
import viaduct.engine.api.EngineObjectData

/** A single-threaded work queue that preserves the recursive resolver's depth-first traversal. */
internal class DepthFirstReactor(
    private val operation: OperationContext,
    private val complete: (SelectionForest) -> SelectionForest,
    private val source: EngineObjectData.Sync,
    private val selections: SelectionForest,
    private val onTaskStarted: (Task) -> Unit = {},
) {
    private val world: Assumptions = operation.world

    sealed interface Task {
        val path: List<PathComponent>
    }

    class SlotOrchestrator(
        override val path: List<PathComponent>,
        val source: EngineObjectData.Sync,
        val selections: SelectionForest,
        val target: ObjectEngineResult,
    ) : Task {
        init {
            require(source.schemaType == target.type) {
                "Source type ${source.schemaType.name} does not match result type ${target.type.name}"
            }
        }
    }

    class SlotResolver(
        override val path: List<PathComponent>,
        val source: EngineObjectData.Sync,
        val selection: ObjectSelection,
        val target: ObjectEngineResult,
    ) : Task {
        init {
            require(source.schemaType == target.type) {
                "Source type ${source.schemaType.name} does not match result type ${target.type.name}"
            }
            require(selection.key.field.containingDef == target.type) {
                "Resolver selection does not belong to its target object"
            }
        }
    }

    private val result =
        context(operation, world) {
            ObjectEngineResult.of(source.schemaType, emptyMap(), mutable = true)
        }
    private val depthFirstResolve = DepthFirstResolve(operation, complete)
    private val tasks = PriorityQueue(depthFirstTaskComparator)
    private val launchedOrchestrators = mutableSetOf<List<PathComponent>>()
    private val startedOrchestrators = mutableSetOf<List<PathComponent>>()
    private val finishedOrchestrators = mutableSetOf<List<PathComponent>>()
    private val orchestratorResults = mutableListOf<OrchestratorResult>()
    private val launchedResolvers = mutableSetOf<List<PathComponent>>()
    private val startedResolvers = mutableSetOf<List<PathComponent>>()
    private val finishedResolvers = mutableSetOf<List<PathComponent>>()
    private var nextSequence = 0L
    private var started = false

    /** Constructs this reactor's result. May be called exactly once. */
    fun resolve(): ObjectEngineResult {
        check(!started) { "DepthFirstReactor.resolve() may only be called once" }
        started = true
        enqueue(
            SlotOrchestrator(
                path = emptyList(),
                source = source,
                selections = selections,
                target = result,
            ),
        )

        while (tasks.isNotEmpty()) {
            val task = tasks.remove().task
            when (task) {
                is SlotOrchestrator -> {
                    check(task.path in launchedOrchestrators) {
                        "Orchestrator started before launching: ${task.path.renderReactorPath()}"
                    }
                    check(startedOrchestrators.add(task.path)) {
                        "Orchestrator started more than once: ${task.path.renderReactorPath()}"
                    }
                    onTaskStarted(task)
                    task.execute()
                }

                is SlotResolver -> {
                    check(task.coordinate in launchedResolvers) {
                        "Resolver coordinate started before launching: " +
                            task.coordinate.renderReactorPath()
                    }
                    check(startedResolvers.add(task.coordinate)) {
                        "Resolver coordinate started more than once: " +
                            task.coordinate.renderReactorPath()
                    }
                    onTaskStarted(task)
                    task.execute()
                }
            }
        }
        check(startedOrchestrators == launchedOrchestrators) {
            "Started orchestrators do not equal launched orchestrators"
        }
        check(finishedOrchestrators == launchedOrchestrators) {
            "Finished orchestrators do not equal launched orchestrators"
        }
        check(startedResolvers == launchedResolvers) {
            "Started resolver coordinates do not equal launched coordinates"
        }
        check(finishedResolvers == launchedResolvers) {
            "Finished resolver coordinates do not equal launched coordinates"
        }
        orchestratorResults.forEach { orchestratorResult ->
            val missing =
                orchestratorResult.closedDemand.groundKeys() -
                    orchestratorResult.target.requireGroundKeys()
            check(missing.isEmpty()) {
                "Completed OER ${orchestratorResult.path.renderReactorPath()} is missing sealed " +
                    "demand: " +
                    missing.joinToString { key ->
                        (orchestratorResult.path + key).renderReactorPath()
                    }
            }
        }
        return result
    }

    private fun SlotOrchestrator.execute() = context(operation, world) {
        val closedDemand = source.closeResolverDemand(result, path, selections)
        require(closedDemand.groundKeys().none { key -> key is ObjectEngineResult.ParentKey }) {
            "Resolver06-08 do not support @parent fields"
        }
        source.materializedChildOccurrences(path, closedDemand, target)
            .forEach { passiveObjectOccurrence ->
                enqueue(
                    SlotOrchestrator(
                        path = passiveObjectOccurrence.path,
                        source = passiveObjectOccurrence.source,
                        selections = passiveObjectOccurrence.selections,
                        target = passiveObjectOccurrence.target,
                    ),
                )
            }
        val unresolvedKeys = closedDemand.groundKeys() - target.requireGroundKeys()
        depthFirstResolve
            .dependencyOrder(source, result, path, unresolvedKeys)
            .forEach { key ->
                enqueue(
                    SlotResolver(
                        path = path,
                        source = source,
                        selection = closedDemand[key],
                        target = target,
                    ),
                )
            }
        check(path in startedOrchestrators) {
            "Orchestrator finished before starting: ${path.renderReactorPath()}"
        }
        check(finishedOrchestrators.add(path)) {
            "Orchestrator finished more than once: ${path.renderReactorPath()}"
        }
        orchestratorResults += OrchestratorResult(path, target, closedDemand)
    }

    private fun SlotResolver.execute() = context(operation, world) {
        depthFirstResolve
            .resolveKey(source, result, path, selection, target)
            ?.resolveRetainedObjects { passiveObjectOccurrence ->
                enqueue(
                    SlotOrchestrator(
                        path = passiveObjectOccurrence.path,
                        source = passiveObjectOccurrence.source,
                        selections = passiveObjectOccurrence.selections,
                        target = passiveObjectOccurrence.target,
                    ),
                )
            }
        check(coordinate in startedResolvers) {
            "Resolver coordinate finished before starting: ${coordinate.renderReactorPath()}"
        }
        check(finishedResolvers.add(coordinate)) {
            "Resolver coordinate finished more than once: ${coordinate.renderReactorPath()}"
        }
    }

    private fun enqueue(task: Task) {
        when (task) {
            is SlotOrchestrator -> {
                check(launchedOrchestrators.add(task.path)) {
                    "Orchestrator launched more than once: ${task.path.renderReactorPath()}"
                }
            }

            is SlotResolver -> {
                check(launchedResolvers.add(task.coordinate)) {
                    "Resolver coordinate launched more than once: " +
                        task.coordinate.renderReactorPath()
                }
            }
        }
        tasks += ScheduledTask(task, nextSequence)
        nextSequence += 1
    }

    private val SlotResolver.coordinate: List<PathComponent>
        get() = path + selection.groundKey()
}

private data class OrchestratorResult(
    val path: List<PathComponent>,
    val target: ObjectEngineResult,
    val closedDemand: ObjectSelectionForest,
)

private fun List<PathComponent>.renderReactorPath(): String =
    if (isEmpty()) {
        "<root>"
    } else {
        joinToString(separator = "/") { component ->
            when (component) {
                is ObjectEngineResult.ObjectKey ->
                    "${component.field.containingDef.name}.${component.field.name}" +
                        when (val arguments = component.arguments) {
                            Arguments.Error -> "(error)"
                            is Arguments.Resolved ->
                                arguments.fieldValues.entries.joinToString(
                                    prefix = "(",
                                    postfix = ")",
                                ) { (name, value) -> "$name=$value" }
                            else -> "(symbolic)"
                        }
                is ListEngineResult.Index -> "[${component.index}]"
            }
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
