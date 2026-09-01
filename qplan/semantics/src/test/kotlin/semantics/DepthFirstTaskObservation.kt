package semantics

import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import semantics.contract.ResolverTaskObservation

internal fun ReactorEvent.toContractObservation(): ResolverTaskObservation? =
    when (this) {
        is ReactorEvent.OrchestratorStarted ->
            ResolverTaskObservation.SlotOrchestrator(
                objectType = objectType,
                path = path.toContractObservationPath(),
            )

        is ReactorEvent.ResolverStarted -> {
            val key = coordinate.last() as ObjectEngineResult.GroundKey
            ResolverTaskObservation.SlotResolver(
                fieldName = key.field.name,
                path = coordinate.dropLast(1).toContractObservationPath(),
            )
        }

        else -> null
    }

private fun List<PathComponent>.toContractObservationPath(): List<String> =
    map { component ->
        when (component) {
            is ObjectEngineResult.ObjectKey -> component.field.name
            is ListEngineResult.Index -> "[${component.index}]"
        }
    }
