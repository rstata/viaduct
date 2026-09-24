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
    // `accumulatedDemand` will become all construction demand rooted at this OER.
    var accumulatedDemand: SelectionForest = initialDemand

    // Unlike Resolver26, these resolvers ground each key before expanding its fixed input.
    val expandedResolverKeys = linkedSetOf<ObjectEngineResult.GroundKey>()

    var demandNotClosed: Boolean
    do {
        // Assume optimistically that demand is closed. Discovering another resolver key below
        // adds its fixed input demand and requires another pass.
        demandNotClosed = false

        val liftedParentDemand =
            accumulatedDemand.liftParentConstructionDemand(operation.world)
        val mergedDemand =
            (accumulatedDemand + liftedParentDemand)
                .applicableGroundSelections(operation, schemaType)

        val newResolverKeys =
            mergedDemand.groundKeys().filter { key ->
                key !in expandedResolverKeys &&
                    !key.arguments.argumentsContainErrorValue() &&
                    key.field in operation.world.resolverRegistry &&
                    requiresStandardResolution(key)
            }.toSet()

        if (newResolverKeys.isNotEmpty()) {
            demandNotClosed = true
            newResolverKeys.bindFromArguments(operation, occurrence.root, occurrence.path)
            val resolverInputDemand =
                newResolverKeys.flatMapToSelectionForest { key ->
                    operation.world.resolverRegistry
                        .resolver(key.field)
                        .instantiateFragmentsAt(occurrence.root, occurrence.coordinate(key))
                        .objectFragment
                        .constructionSelections
                }
            accumulatedDemand = mergedDemand + resolverInputDemand
            expandedResolverKeys += newResolverKeys
        }
    } while (demandNotClosed)

    val closedDemand =
        (accumulatedDemand + accumulatedDemand.liftParentConstructionDemand(operation.world))
            .applicableGroundSelections(operation, schemaType)
    return closedDemand
}

private fun EngineObjectData.Sync.requiresStandardResolution(key: ObjectEngineResult.GroundKey): Boolean {
    if (!isPresent(key.field.name)) return true
    require(key.field.args.isEmpty()) {
        "Resolver output must not supply argument-bearing field " +
            "${schemaType.name}/${key.field.name}"
    }
    return false
}
