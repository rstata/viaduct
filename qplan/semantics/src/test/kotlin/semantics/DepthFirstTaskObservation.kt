package semantics

import model.PathComponent
import model.Value
import model.groundKey
import semantics.contract.ResolverTaskObservation

internal fun DepthFirstReactor.Task.toContractObservation(): ResolverTaskObservation =
    when (this) {
        is DepthFirstReactor.SlotOrchestrator ->
            ResolverTaskObservation.SlotOrchestrator(
                objectType = source.type.typeName,
                path = path.toContractObservationPath(),
            )

        is DepthFirstReactor.SlotResolver ->
            ResolverTaskObservation.SlotResolver(
                fieldName = selection.groundKey().field.fieldName,
                path = path.toContractObservationPath(),
            )
    }

private fun List<PathComponent>.toContractObservationPath(): List<String> =
    map { component ->
        when (component) {
            is Value.GroundKey -> component.field.fieldName
            is Value.ListIndex -> "[${component.index}]"
        }
    }
