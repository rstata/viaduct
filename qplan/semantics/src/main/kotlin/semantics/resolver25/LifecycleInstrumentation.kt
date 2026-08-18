package semantics.resolver25

import model.ObjectEngineResult

import model.ObjectSelection
import model.PathComponent
import model.Schema
import model.Selection
import model.Value
import model.VariableBinding

internal sealed interface Resolver25BindingSource {
    data class FromArgument(
        val argumentName: String,
    ) : Resolver25BindingSource

    data class FromObjectField(
        val providerPath: List<ObjectEngineResult.Key>,
    ) : Resolver25BindingSource
}

internal enum class Resolver25KeyKind {
    FIELD_RESOLVER,
    ERROR,
    PASSIVE,
    PREEXISTING,
}

@JvmInline
internal value class DemandContributionId(
    val submittedAt: Long,
)

/** Immutable Resolver25 lifecycle observations ordered by [sequence]. */
internal sealed interface Resolver25LifecycleEvent {
    val sequence: Long

    data class OrchestratorCreated(
        override val sequence: Long,
        val path: List<PathComponent>,
        val objectType: String,
    ) : Resolver25LifecycleEvent

    data class OrchestratorReady(
        override val sequence: Long,
        val path: List<PathComponent>,
    ) : Resolver25LifecycleEvent

    data class DemandSubmitted(
        override val sequence: Long,
        val path: List<PathComponent>,
        val consumerCoordinate: List<PathComponent>?,
        val selection: Selection,
    ) : Resolver25LifecycleEvent {
        val contributionId: DemandContributionId
            get() = DemandContributionId(sequence)
    }

    data class DemandGrounded(
        override val sequence: Long,
        val contributionId: DemandContributionId,
        val coordinate: List<PathComponent>,
    ) : Resolver25LifecycleEvent

    data class GroundedKeyInterned(
        override val sequence: Long,
        val contributionId: DemandContributionId,
        val coordinate: List<PathComponent>,
        val kind: Resolver25KeyKind,
    ) : Resolver25LifecycleEvent

    data class GroundedDemandMerged(
        override val sequence: Long,
        val contributionId: DemandContributionId,
        val coordinate: List<PathComponent>,
        val beforeLaunch: Boolean,
    ) : Resolver25LifecycleEvent

    data class ValuePromiseInstalled(
        override val sequence: Long,
        val coordinate: List<PathComponent>,
    ) : Resolver25LifecycleEvent

    data class DemandSealed(
        override val sequence: Long,
        val coordinate: List<PathComponent>,
        val demand: ObjectSelection,
    ) : Resolver25LifecycleEvent

    data class BindingDeclared(
        override val sequence: Long,
        val ownerCoordinate: List<PathComponent>,
        val variable: Value.Variable,
        val source: Resolver25BindingSource,
    ) : Resolver25LifecycleEvent

    data class BindingCompleted(
        override val sequence: Long,
        val ownerCoordinate: List<PathComponent>,
        val variable: Value.Variable,
        val binding: VariableBinding,
    ) : Resolver25LifecycleEvent

    data class ResolverStarted(
        override val sequence: Long,
        val coordinate: List<PathComponent>,
    ) : Resolver25LifecycleEvent

    data class ResolverFinished(
        override val sequence: Long,
        val coordinate: List<PathComponent>,
    ) : Resolver25LifecycleEvent

    data class OutputAvailable(
        override val sequence: Long,
        val coordinate: List<PathComponent>,
    ) : Resolver25LifecycleEvent

    data class ChildOrchestratorRequired(
        override val sequence: Long,
        val parentCoordinate: List<PathComponent>,
        val childPath: List<PathComponent>,
    ) : Resolver25LifecycleEvent

    data class KeyActivationReady(
        override val sequence: Long,
        val coordinate: List<PathComponent>,
    ) : Resolver25LifecycleEvent

    data class ContributionInstalled(
        override val sequence: Long,
        val contributionId: DemandContributionId,
        val coordinate: List<PathComponent>,
    ) : Resolver25LifecycleEvent

    data class ValuePublished(
        override val sequence: Long,
        val coordinate: List<PathComponent>,
    ) : Resolver25LifecycleEvent
}

/**
 * Receives transitions immediately before their latches become visible to suspended consumers.
 *
 * Observers must only record the immutable event; throwing or reentering resolution is unsupported.
 */
internal typealias Resolver25LifecycleEventObserver = (Resolver25LifecycleEvent) -> Unit

/** Assigns one process-local sequence to every emitted event and performs no validation. */
internal class Resolver25LifecycleInstrumentation(
    private val observer: Resolver25LifecycleEventObserver? = null,
) {
    private var nextSequence: Long = 0

    fun orchestratorCreated(
        path: List<PathComponent>,
        objectType: Schema.ObjectType,
    ) {
        emit { sequence ->
            Resolver25LifecycleEvent.OrchestratorCreated(
                sequence = sequence,
                path = path.toList(),
                objectType = objectType.typeName,
            )
        }
    }

    fun orchestratorReady(path: List<PathComponent>) {
        emit { sequence ->
            Resolver25LifecycleEvent.OrchestratorReady(sequence, path.toList())
        }
    }

    fun demandSubmitted(
        path: List<PathComponent>,
        consumerCoordinate: List<PathComponent>?,
        selection: Selection,
    ): DemandContributionId {
        val sequence = nextSequence++
        observer?.invoke(
                Resolver25LifecycleEvent.DemandSubmitted(
                    sequence,
                    path.toList(),
                    consumerCoordinate?.toList(),
                    selection,
            ),
        )
        return DemandContributionId(sequence)
    }

    fun demandGrounded(
        contributionId: DemandContributionId,
        coordinate: List<PathComponent>,
    ) {
        emit { sequence ->
            Resolver25LifecycleEvent.DemandGrounded(
                sequence,
                contributionId,
                coordinate.toList(),
            )
        }
    }

    fun groundedKeyInterned(
        contributionId: DemandContributionId,
        coordinate: List<PathComponent>,
        kind: Resolver25KeyKind,
    ) {
        emit { sequence ->
            Resolver25LifecycleEvent.GroundedKeyInterned(
                sequence,
                contributionId,
                coordinate.toList(),
                kind,
            )
        }
    }

    fun groundedDemandMerged(
        contributionId: DemandContributionId,
        coordinate: List<PathComponent>,
        beforeLaunch: Boolean,
    ) {
        emit { sequence ->
            Resolver25LifecycleEvent.GroundedDemandMerged(
                sequence,
                contributionId,
                coordinate.toList(),
                beforeLaunch,
            )
        }
    }

    fun valuePromiseInstalled(coordinate: List<PathComponent>) {
        emit { sequence ->
            Resolver25LifecycleEvent.ValuePromiseInstalled(
                sequence,
                coordinate.toList(),
            )
        }
    }

    fun demandSealed(
        coordinate: List<PathComponent>,
        demand: ObjectSelection,
    ) {
        emit { sequence ->
            Resolver25LifecycleEvent.DemandSealed(
                sequence,
                coordinate.toList(),
                demand,
            )
        }
    }

    fun bindingDeclared(
        ownerCoordinate: List<PathComponent>,
        variable: Value.Variable,
        source: Resolver25BindingSource,
    ) {
        emit { sequence ->
            Resolver25LifecycleEvent.BindingDeclared(
                sequence,
                ownerCoordinate.toList(),
                variable,
                source,
            )
        }
    }

    fun bindingCompleted(
        ownerCoordinate: List<PathComponent>,
        variable: Value.Variable,
        binding: VariableBinding,
    ) {
        emit { sequence ->
            Resolver25LifecycleEvent.BindingCompleted(
                sequence,
                ownerCoordinate.toList(),
                variable,
                binding,
            )
        }
    }

    fun resolverStarted(coordinate: List<PathComponent>) {
        emit { sequence ->
            Resolver25LifecycleEvent.ResolverStarted(sequence, coordinate.toList())
        }
    }

    fun resolverFinished(coordinate: List<PathComponent>) {
        emit { sequence ->
            Resolver25LifecycleEvent.ResolverFinished(sequence, coordinate.toList())
        }
    }

    fun outputAvailable(coordinate: List<PathComponent>) {
        emit { sequence ->
            Resolver25LifecycleEvent.OutputAvailable(sequence, coordinate.toList())
        }
    }

    fun childOrchestratorRequired(
        parentCoordinate: List<PathComponent>,
        childPath: List<PathComponent>,
    ) {
        emit { sequence ->
            Resolver25LifecycleEvent.ChildOrchestratorRequired(
                sequence,
                parentCoordinate.toList(),
                childPath.toList(),
            )
        }
    }

    fun keyActivationReady(coordinate: List<PathComponent>) {
        emit { sequence ->
            Resolver25LifecycleEvent.KeyActivationReady(
                sequence,
                coordinate.toList(),
            )
        }
    }

    fun contributionInstalled(
        contributionId: DemandContributionId,
        coordinate: List<PathComponent>,
    ) {
        emit { sequence ->
            Resolver25LifecycleEvent.ContributionInstalled(
                sequence,
                contributionId,
                coordinate.toList(),
            )
        }
    }

    fun valuePublished(coordinate: List<PathComponent>) {
        emit { sequence ->
            Resolver25LifecycleEvent.ValuePublished(sequence, coordinate.toList())
        }
    }

    private inline fun emit(event: (Long) -> Resolver25LifecycleEvent) {
        val sequence = nextSequence++
        observer?.invoke(event(sequence))
    }
}
