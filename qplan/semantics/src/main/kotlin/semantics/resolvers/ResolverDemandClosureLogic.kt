package semantics.resolvers

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.SelectionForest
import model.flatMapToSelectionForest
import model.schemaType
import model.selectionForestOf
import semantics.correctresolution.argumentsContainErrorValue
import semantics.shared.OEROccurrence
import semantics.shared.SharedOperationContext
import semantics.shared.applicableGroundSelections
import semantics.shared.inputParentDemand
import viaduct.engine.api.EngineObjectData

/**
 * Closes construction demand for one object occurrence against its fixed source and parent policy.
 *
 * Each step grounds selections under existing bindings, binds variables for newly discovered
 * standard resolvers, and adds their direct object-fragment demand at this occurrence. Fields
 * supplied by the source remain passive. Only demand and the expanded-key set change between steps.
 */
internal class ResolverDemandClosureLogic(
    private val operation: SharedOperationContext<*>,
    private val oerOccurrence: OEROccurrence,
    private val source: EngineObjectData.Sync,
    private val includeParentInputDemand: Boolean = false,
) {
    fun close(selections: SelectionForest): ObjectSelectionForest =
        close(selections, emptySet())

    private fun close(
        selections: SelectionForest,
        expanded: Set<ObjectEngineResult.GroundKey>,
    ): ObjectSelectionForest {
        val parentInputDemand =
            if (includeParentInputDemand) {
                selections.inputParentDemand(operation.world)
            } else {
                selectionForestOf()
            }
        val applicableSelections =
            (selections + parentInputDemand).applicableGroundSelections(operation, source.schemaType)
        val unexpandedResolverKeys =
            applicableSelections.groundKeys().filter { key ->
                key !in expanded &&
                    !key.arguments.argumentsContainErrorValue() &&
                    key.field in operation.world.resolverRegistry &&
                    requiresStandardResolution(key)
            }.toSet()

        if (unexpandedResolverKeys.isEmpty()) return applicableSelections

        unexpandedResolverKeys.bindFromArguments(operation, oerOccurrence.root, oerOccurrence.path)
        val resolverDemand =
            unexpandedResolverKeys.flatMapToSelectionForest { key ->
                operation.world.resolverRegistry
                    .resolver(key.field)
                    .instantiateFragmentsAt(oerOccurrence.root, oerOccurrence.coordinate(key))
                    .objectFragment
                    .constructionSelections
            }
        return close(
            selections = applicableSelections + resolverDemand,
            expanded = expanded + unexpandedResolverKeys,
        )
    }

    private fun requiresStandardResolution(key: ObjectEngineResult.GroundKey): Boolean {
        if (!source.isPresent(key.field.name)) return true
        require(key.field.args.isEmpty()) {
            "Resolver output must not supply argument-bearing field " +
                "${source.schemaType.name}/${key.field.name}"
        }
        return false
    }
}
