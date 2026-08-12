package semantics.resolver25

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.assertValidResolver25LifecycleTrace

internal data class Resolver25ResolutionObservation(
    val result: EngineResult.Object,
    val lifecycleEvents: List<Resolver25LifecycleEvent>,
)

internal fun resolveWithLifecycleValidation(
    world: Assumptions,
    root: Value.Object,
    selections: SelectionForest,
): EngineResult.Object =
    observeWithLifecycleValidation(world, root, selections).result

internal fun observeWithLifecycleValidation(
    world: Assumptions,
    root: Value.Object,
    selections: SelectionForest,
): Resolver25ResolutionObservation =
    observeResolver25Resolution(world, root, selections)
        .also { observation ->
            observation.lifecycleEvents.assertValidResolver25LifecycleTrace()
        }

internal fun observeResolver25Resolution(
    world: Assumptions,
    root: Value.Object,
    selections: SelectionForest,
): Resolver25ResolutionObservation {
    val events = mutableListOf<Resolver25LifecycleEvent>()
    val result =
        context(world) {
            root.resolveObserved(selections, events::add)
        }
    return Resolver25ResolutionObservation(
        result = result,
        lifecycleEvents = events.toList(),
    )
}
