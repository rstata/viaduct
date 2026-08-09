package semantics

import model.EngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.Value
import semantics.correctresolution.argumentsContainErrorValue

/** The kind of work performed by one slot resolver. */
internal enum class ReactorSlotKind {
    FIELD_RESOLVER,
    ENGINE_OWNED,
    PASSIVE,
}

/** Reactor lifecycle observations shared across scheduler implementations. */
internal sealed interface ReactorEvent {
    data class OrchestratorLaunched(
        val path: List<PathComponent>,
        val objectType: String,
    ) : ReactorEvent

    data class OrchestratorStarted(
        val path: List<PathComponent>,
        val objectType: String,
    ) : ReactorEvent

    data class OrchestratorFinished(
        val path: List<PathComponent>,
        val objectType: String,
    ) : ReactorEvent

    data class ResolverLaunched(
        val coordinate: List<PathComponent>,
        val kind: ReactorSlotKind,
    ) : ReactorEvent

    data class ResolverStarted(
        val coordinate: List<PathComponent>,
        val kind: ReactorSlotKind,
    ) : ReactorEvent

    data class ResolverFinished(
        val coordinate: List<PathComponent>,
        val kind: ReactorSlotKind,
    ) : ReactorEvent

    data class ReadinessEvaluated(
        val coordinate: List<PathComponent>,
        val requiredCoordinates: Set<List<PathComponent>>,
        val absentCoordinates: Set<List<PathComponent>>,
    ) : ReactorEvent

    data class ResolverDependenciesApplied(
        val coordinate: List<PathComponent>,
        val dependencyCoordinates: Set<List<PathComponent>>,
    ) : ReactorEvent

    data class SlotRegistered(
        val coordinate: List<PathComponent>,
    ) : ReactorEvent

    data class ResolverOccurrenceExpanded(
        val coordinate: List<PathComponent>,
    ) : ReactorEvent

    data class SlotSealed(
        val coordinate: List<PathComponent>,
    ) : ReactorEvent

    data class ObjectPathVariableBound(
        val ownerCoordinate: List<PathComponent>,
        val variable: Value.Variable.Stamped,
        val providerPath: List<Value.Key>,
        val value: Value.Input?,
    ) : ReactorEvent
}

internal typealias ReactorEventObserver = (ReactorEvent) -> Unit

context(world: model.Assumptions)
internal fun Value.GroundKey.reactorSlotKind(): ReactorSlotKind =
    when {
        arguments.argumentsContainErrorValue() ||
            field.fieldName == "__typename" ->
            ReactorSlotKind.ENGINE_OWNED
        field in world.resolverRegistry -> ReactorSlotKind.FIELD_RESOLVER
        else -> ReactorSlotKind.PASSIVE
    }

/**
 * Records scheduler-independent reactor lifecycle state and checks its invariants.
 *
 * Reactors retain ownership of their queues and scheduling-specific state.
 */
internal class ReactorInstrumentation(
    private val eventObserver: ReactorEventObserver = {},
) {
    private val launchedOrchestrators = mutableMapOf<List<PathComponent>, String>()
    private val startedOrchestrators = mutableSetOf<List<PathComponent>>()
    private val finishedOrchestrators = mutableSetOf<List<PathComponent>>()
    private val orchestratorResults = mutableListOf<OrchestratorResult>()
    private val launchedResolvers = mutableMapOf<List<PathComponent>, ReactorSlotKind>()
    private val startedResolvers = mutableSetOf<List<PathComponent>>()
    private val finishedResolvers = mutableSetOf<List<PathComponent>>()
    private val appliedResolverDependencies =
        mutableMapOf<List<PathComponent>, Set<List<PathComponent>>>()
    private val registeredSlots = mutableSetOf<List<PathComponent>>()
    private val expandedOccurrences = mutableSetOf<List<PathComponent>>()
    private val sealedSlots = mutableSetOf<List<PathComponent>>()
    private val boundObjectPathVariables = mutableSetOf<Value.Variable.Stamped>()

    fun orchestratorLaunched(
        path: List<PathComponent>,
        objectType: String,
    ) {
        check(launchedOrchestrators.putIfAbsent(path, objectType) == null) {
            "Orchestrator launched more than once: ${path.renderReactorPath()}"
        }
        eventObserver(ReactorEvent.OrchestratorLaunched(path, objectType))
    }

    fun orchestratorStarted(path: List<PathComponent>) {
        val objectType = launchedOrchestrators.getValue(path)
        check(startedOrchestrators.add(path)) {
            "Orchestrator started more than once: ${path.renderReactorPath()}"
        }
        eventObserver(ReactorEvent.OrchestratorStarted(path, objectType))
    }

    fun orchestratorFinished(
        path: List<PathComponent>,
        target: EngineResult.Object,
        closedDemand: ObjectSelectionForest,
    ) {
        val objectType = launchedOrchestrators.getValue(path)
        check(path in startedOrchestrators) {
            "Orchestrator finished before starting: ${path.renderReactorPath()}"
        }
        check(finishedOrchestrators.add(path)) {
            "Orchestrator finished more than once: ${path.renderReactorPath()}"
        }
        orchestratorResults += OrchestratorResult(path, target, closedDemand)
        eventObserver(ReactorEvent.OrchestratorFinished(path, objectType))
    }

    fun resolverLaunched(
        coordinate: List<PathComponent>,
        kind: ReactorSlotKind,
    ) {
        check(launchedResolvers.putIfAbsent(coordinate, kind) == null) {
            "Resolver coordinate launched more than once: ${coordinate.renderReactorPath()}"
        }
        eventObserver(ReactorEvent.ResolverLaunched(coordinate, kind))
    }

    fun resolverStarted(coordinate: List<PathComponent>) {
        val kind = launchedResolvers.getValue(coordinate)
        check(startedResolvers.add(coordinate)) {
            "Resolver coordinate started more than once: ${coordinate.renderReactorPath()}"
        }
        eventObserver(ReactorEvent.ResolverStarted(coordinate, kind))
    }

    fun resolverFinished(coordinate: List<PathComponent>) {
        val kind = launchedResolvers.getValue(coordinate)
        check(coordinate in startedResolvers) {
            "Resolver coordinate finished before starting: ${coordinate.renderReactorPath()}"
        }
        check(finishedResolvers.add(coordinate)) {
            "Resolver coordinate finished more than once: ${coordinate.renderReactorPath()}"
        }
        eventObserver(ReactorEvent.ResolverFinished(coordinate, kind))
    }

    fun readinessEvaluated(
        coordinate: List<PathComponent>,
        requiredCoordinates: Set<List<PathComponent>>,
        absentCoordinates: Set<List<PathComponent>>,
    ) {
        eventObserver(
            ReactorEvent.ReadinessEvaluated(
                coordinate = coordinate,
                requiredCoordinates = requiredCoordinates,
                absentCoordinates = absentCoordinates,
            ),
        )
    }

    fun resolverDependenciesApplied(
        coordinate: List<PathComponent>,
        dependencyCoordinates: Set<List<PathComponent>>,
    ) {
        check(
            appliedResolverDependencies.putIfAbsent(
                coordinate,
                dependencyCoordinates,
            ) == null,
        ) {
            "Resolver dependencies applied more than once: ${coordinate.renderReactorPath()}"
        }
        eventObserver(
            ReactorEvent.ResolverDependenciesApplied(
                coordinate = coordinate,
                dependencyCoordinates = dependencyCoordinates,
            ),
        )
    }

    fun slotRegistered(coordinate: List<PathComponent>) {
        check(registeredSlots.add(coordinate)) {
            "Slot registered more than once: ${coordinate.renderReactorPath()}"
        }
        eventObserver(ReactorEvent.SlotRegistered(coordinate))
    }

    fun resolverOccurrenceExpanded(coordinate: List<PathComponent>) {
        check(expandedOccurrences.add(coordinate)) {
            "Resolver occurrence expanded more than once: ${coordinate.renderReactorPath()}"
        }
        eventObserver(ReactorEvent.ResolverOccurrenceExpanded(coordinate))
    }

    fun slotSealed(coordinate: List<PathComponent>) {
        check(coordinate in registeredSlots) {
            "Slot sealed before registration: ${coordinate.renderReactorPath()}"
        }
        check(sealedSlots.add(coordinate)) {
            "Slot sealed more than once: ${coordinate.renderReactorPath()}"
        }
        eventObserver(ReactorEvent.SlotSealed(coordinate))
    }

    fun objectPathVariableBound(
        ownerCoordinate: List<PathComponent>,
        variable: Value.Variable.Stamped,
        providerPath: List<Value.Key>,
        value: Value.Input?,
    ) {
        check(boundObjectPathVariables.add(variable)) {
            "Object-path variable bound more than once: $variable"
        }
        eventObserver(
            ReactorEvent.ObjectPathVariableBound(
                ownerCoordinate = ownerCoordinate,
                variable = variable,
                providerPath = providerPath,
                value = value,
            ),
        )
    }

    fun resolutionFinished() {
        check(startedOrchestrators == launchedOrchestrators.keys) {
            "Started orchestrators do not equal launched orchestrators"
        }
        check(finishedOrchestrators == launchedOrchestrators.keys) {
            "Finished orchestrators do not equal launched orchestrators"
        }
        check(startedResolvers == launchedResolvers.keys) {
            "Started resolver coordinates do not equal launched coordinates"
        }
        check(finishedResolvers == launchedResolvers.keys) {
            "Finished resolver coordinates do not equal launched coordinates"
        }
        check(registeredSlots == sealedSlots) {
            "Registered slots do not equal sealed slots"
        }
        orchestratorResults.forEach { result ->
            val missing = result.closedDemand.groundKeys() - result.target.keys
            check(missing.isEmpty()) {
                "Completed OER ${result.path.renderReactorPath()} is missing sealed demand: " +
                    missing.joinToString { key ->
                        (result.path + key).renderReactorPath()
                    }
            }
        }
    }
}

private data class OrchestratorResult(
    val path: List<PathComponent>,
    val target: EngineResult.Object,
    val closedDemand: ObjectSelectionForest,
)

internal fun List<PathComponent>.renderReactorPath(): String =
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
