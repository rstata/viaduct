package semantics

import model.PathComponent
import model.Value
import semantics.contract.ResolverTaskObservation

internal fun ReactorEvent.toContractObservation(): ResolverTaskObservation? =
    when (this) {
        is ReactorEvent.OrchestratorStarted ->
            ResolverTaskObservation.SlotOrchestrator(
                objectType = objectType,
                path = path.toContractObservationPath(),
            )

        is ReactorEvent.ResolverStarted -> {
            val key = coordinate.last() as Value.GroundKey
            ResolverTaskObservation.SlotResolver(
                fieldName = key.field.fieldName,
                path = coordinate.dropLast(1).toContractObservationPath(),
            )
        }

        else -> null
    }

private fun List<PathComponent>.toContractObservationPath(): List<String> =
    map { component ->
        when (component) {
            is Value.GroundKey -> component.field.fieldName
            is Value.ListIndex -> "[${component.index}]"
        }
    }
