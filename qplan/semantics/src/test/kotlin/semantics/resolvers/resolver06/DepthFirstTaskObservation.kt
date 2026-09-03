package semantics.resolvers.resolver06

import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.groundKey
import model.schemaType
import semantics.contract.ResolverTaskObservation

internal fun DepthFirstReactor.Task.toContractObservation(): ResolverTaskObservation =
    when (this) {
        is DepthFirstReactor.SlotOrchestrator ->
            ResolverTaskObservation.SlotOrchestrator(
                objectType = source.schemaType.name,
                path = path.toContractObservationPath(),
            )

        is DepthFirstReactor.SlotResolver -> {
            ResolverTaskObservation.SlotResolver(
                fieldName = selection.groundKey().field.name,
                path = path.toContractObservationPath(),
            )
        }
    }

private fun List<PathComponent>.toContractObservationPath(): List<String> =
    map { component ->
        when (component) {
            is ObjectEngineResult.ObjectKey -> component.field.name
            is ListEngineResult.Index -> "[${component.index}]"
        }
    }
