package semantics.resolvers

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.SelectionForest
import model.flatMapToSelectionForest
import model.schemaType
import semantics.shared.argumentsContainErrorValue
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import semantics.shared.applicableGroundSelections
import semantics.resolver26.liftParentConstructionDemand
import viaduct.engine.api.EngineObjectData

/**
 * Closes construction demand for one object occurrence against its fixed source.
 *
 * Each step grounds selections under existing bindings, binds variables for newly discovered
 * standard resolvers, and adds their direct object-fragment demand at this occurrence. Fields
 * supplied by the source remain passive. Only demand and the expanded-key set change between steps.
 */
internal fun EngineObjectData.Sync.closeConstructionDemand(
    operation: SharedOperationContext<*>,
    occurrence: OEROccurrence,
    initialDemand: SelectionForest,
): ObjectSelectionForest {
    fun close(
        selections: SelectionForest,
        expanded: Set<ObjectEngineResult.GroundKey>,
    ): ObjectSelectionForest {
        val ancestorDemand = selections.liftParentConstructionDemand(operation.world)
        val applicableSelections =
            (selections + ancestorDemand).applicableGroundSelections(operation, schemaType)
        val unexpandedResolverKeys =
            applicableSelections.groundKeys().filter { key ->
                key !in expanded &&
                    !key.arguments.argumentsContainErrorValue() &&
                    key.field in operation.world.resolverRegistry &&
                    requiresStandardResolution(key)
            }.toSet()

        if (unexpandedResolverKeys.isEmpty()) return applicableSelections

        unexpandedResolverKeys.bindFromArguments(operation, occurrence.root, occurrence.path)
        val resolverDemand =
            unexpandedResolverKeys.flatMapToSelectionForest { key ->
                operation.world.resolverRegistry
                    .resolver(key.field)
                    .instantiateFragmentsAt(occurrence.root, occurrence.coordinate(key))
                    .objectFragment
                    .constructionSelections
            }
        return close(
            selections = applicableSelections + resolverDemand,
            expanded = expanded + unexpandedResolverKeys,
        )
    }

    return close(initialDemand, emptySet())
}

private fun EngineObjectData.Sync.requiresStandardResolution(key: ObjectEngineResult.GroundKey): Boolean {
    if (!isPresent(key.field.name)) return true
    require(key.field.args.isEmpty()) {
        "Resolver output must not supply argument-bearing field " +
            "${schemaType.name}/${key.field.name}"
    }
    return false
}
