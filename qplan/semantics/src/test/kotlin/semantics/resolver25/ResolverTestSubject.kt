package semantics.resolver25

import model.Assumptions
import model.EngineResult
import model.PathComponent
import model.SelectionForest
import model.Value
import semantics.contract.ResolverResolutionObservation
import semantics.contract.assertValidResolver25LifecycleTrace

internal data class Resolver25ResolutionObservation(
    override val result: EngineResult.Object,
    val lifecycleEvents: List<Resolver25LifecycleEvent>,
) : ResolverResolutionObservation

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
        try {
            context(world) {
                root.resolveObserved(selections, events::add)
            }
        } catch (failure: Throwable) {
            events.forEach { event ->
                println(event.debugSummary())
            }
            throw failure
        }
    return Resolver25ResolutionObservation(
        result = result,
        lifecycleEvents = events.toList(),
    )
}

private fun Resolver25LifecycleEvent.debugSummary(): String =
    when (this) {
        is Resolver25LifecycleEvent.DemandSubmitted ->
            "$sequence submit ${path.debugSummary()} ${selection.key.field.fieldName} " +
                "sub=${selection.subselections.debugFields()}"
        is Resolver25LifecycleEvent.DemandGrounded ->
            "$sequence ground ${coordinate.debugSummary()}"
        is Resolver25LifecycleEvent.GroundedKeyInterned ->
            "$sequence intern ${coordinate.debugSummary()} $kind"
        is Resolver25LifecycleEvent.GroundedDemandMerged ->
            "$sequence merge ${coordinate.debugSummary()} before=$beforeLaunch"
        is Resolver25LifecycleEvent.DemandSealed ->
            "$sequence seal ${coordinate.debugSummary()} sub=${demand.subselections.debugFields()}"
        is Resolver25LifecycleEvent.ResolverStarted ->
            "$sequence start ${coordinate.debugSummary()}"
        is Resolver25LifecycleEvent.OutputAvailable ->
            "$sequence output ${coordinate.debugSummary()}"
        else -> "$sequence ${this::class.simpleName}"
    }

private fun List<PathComponent>.debugSummary(): String =
    joinToString("/") { component ->
        when (component) {
            is Value.GroundKey -> component.field.debugSummary()
            is Value.ListIndex -> "[${component.index}]"
        }
    }

private fun model.Schema.ObjectField.debugSummary(): String =
    "${containingType.typeName}/$fieldName"

private fun SelectionForest.debugFields(): Set<String> =
    linkedSetOf<String>().also { fields ->
        forEach { selection ->
            fields += selection.key.field.fieldName
        }
    }
