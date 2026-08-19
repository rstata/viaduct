package semantics.resolver25

import viaduct.engine.api.EngineObjectData

import model.Assumptions
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Schema
import model.SelectionForest
import semantics.contract.GeneratedResolutionObservation
import semantics.contract.ResolverResolutionObservation
import semantics.contract.assertValidResolver25LifecycleTrace

internal data class Resolver25ResolutionObservation(
    override val result: ObjectEngineResult,
    val lifecycleEvents: List<Resolver25LifecycleEvent>,
) : ResolverResolutionObservation

internal fun resolveWithLifecycleValidation(
    world: Assumptions,
    root: EngineObjectData.Sync,
    selections: SelectionForest,
): ObjectEngineResult =
    observeWithLifecycleValidation(world, root, selections).result

internal fun observeWithLifecycleValidation(
    world: Assumptions,
    root: EngineObjectData.Sync,
    selections: SelectionForest,
): Resolver25ResolutionObservation =
    observeResolver25Resolution(world, root, selections)
        .also { observation ->
            observation.lifecycleEvents.assertValidResolver25LifecycleTrace()
        }

internal fun observeResolver25Resolution(
    world: Assumptions,
    root: EngineObjectData.Sync,
    selections: SelectionForest,
): Resolver25ResolutionObservation {
    val events = mutableListOf<Resolver25LifecycleEvent>()
    val result =
        try {
            context(world) {
                resolveObserved(selections, events::add)
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
            "$sequence submit ${path.debugSummary()} ${selection.key.field.name} " +
                "sub=${selection.subselections.debugFields()}"
        is Resolver25LifecycleEvent.DemandGrounded ->
            "$sequence ground ${coordinate.debugSummary()}"
        is Resolver25LifecycleEvent.GroundedKeyInterned ->
            "$sequence intern ${coordinate.debugSummary()} $kind"
        is Resolver25LifecycleEvent.GroundedDemandMerged ->
            "$sequence merge ${coordinate.debugSummary()} before=$beforeLaunch"
        is Resolver25LifecycleEvent.DemandSealed ->
            "$sequence seal ${coordinate.debugSummary()} sub=${demand.subselections.debugFields()}"
        is Resolver25LifecycleEvent.BindingDeclared ->
            "$sequence bind ${ownerCoordinate.debugSummary()} $variable source=$source"
        is Resolver25LifecycleEvent.BindingCompleted ->
            "$sequence bound ${ownerCoordinate.debugSummary()} $variable binding=$binding"
        is Resolver25LifecycleEvent.ResolverStarted ->
            "$sequence start ${coordinate.debugSummary()}"
        is Resolver25LifecycleEvent.OutputAvailable ->
            "$sequence output ${coordinate.debugSummary()}"
        else -> "$sequence ${this::class.simpleName}"
    }

private fun List<PathComponent>.debugSummary(): String =
    joinToString("/") { component ->
        when (component) {
            is ObjectEngineResult.GroundKey -> component.field.debugSummary()
            is ListEngineResult.Index -> "[${component.index}]"
        }
    }

private fun Schema.ObjectField.debugSummary(): String =
    "${containingDef.name}/$name"

private fun SelectionForest.debugFields(): Set<String> =
    linkedSetOf<String>().also { fields ->
        forEach { selection ->
            fields += selection.key.field.name
        }
    }
