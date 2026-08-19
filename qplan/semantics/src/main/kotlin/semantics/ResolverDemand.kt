package semantics

import model.ObjectEngineResult

import model.Assumptions
import model.ObjectSelectionForest
import model.PathComponent
import model.Schema
import model.SelectionForest
import model.applicableGroundSelections
import model.flatMapToSelectionForest
import semantics.correctresolution.argumentsContainErrorValue

/**
 * Returns the applicable demand closed under the direct object fragments of its resolver fields.
 *
 * Each closure step normalizes under existing bindings, binds variables defined by newly discovered
 * resolver occurrences, and stamps those occurrences' direct object fragments at their exact paths.
 */
context(world: Assumptions)
fun Schema.ObjectType.closeResolverDemand(
    path: List<PathComponent>,
    selections: SelectionForest,
): ObjectSelectionForest =
    closeResolverDemand(
        path = path,
        selections = selections,
        expanded = emptySet(),
    )

context(world: Assumptions)
private fun Schema.ObjectType.closeResolverDemand(
    path: List<PathComponent>,
    selections: SelectionForest,
    expanded: Set<ObjectEngineResult.GroundKey>,
): ObjectSelectionForest {
    val applicableSelections = selections.applicableGroundSelections(this)
    val unexpandedResolverKeys =
        applicableSelections.groundKeys().filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in world.resolverRegistry
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) return applicableSelections

    unexpandedResolverKeys.bindFromArguments(path)
    val resolverDemand =
        unexpandedResolverKeys.flatMapToSelectionForest { key ->
            world.resolverRegistry
                .resolver(key.field)
                .stampVars(path + key)
        }
    return closeResolverDemand(
        path = path,
        selections = applicableSelections + resolverDemand,
        expanded = expanded + unexpandedResolverKeys,
    )
}
