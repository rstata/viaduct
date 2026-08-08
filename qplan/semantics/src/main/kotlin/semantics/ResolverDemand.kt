package semantics

import model.Assumptions
import model.GroundSelectionForest
import model.PathComponent
import model.Schema
import model.SelectionForest
import model.Value
import model.mergeToGround
import model.selectionForestOf
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
): GroundSelectionForest =
    closeResolverDemand(
        path = path,
        selections = selections,
        expanded = emptySet(),
    )

context(world: Assumptions)
private fun Schema.ObjectType.closeResolverDemand(
    path: List<PathComponent>,
    selections: SelectionForest,
    expanded: Set<Value.GroundKey>,
): GroundSelectionForest {
    val applicableSelections = selections.mergeToGround(this)
    val unexpandedResolverKeys =
        applicableSelections.keys().filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in world.resolverRegistry
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) return applicableSelections

    unexpandedResolverKeys.bindFromArguments(path)
    val resolverDemand =
        unexpandedResolverKeys.fold(selectionForestOf()) { demand, key ->
            demand +
                world.resolverRegistry
                    .resolver(key.field)
                    .stampedObjectFragment(path + key)
        }
    return closeResolverDemand(
        path = path,
        selections = applicableSelections + resolverDemand,
        expanded = expanded + unexpandedResolverKeys,
    )
}
