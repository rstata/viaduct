package semantics.shared

import model.Arguments
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.SelectionForest
import model.concatenateSelectionForests
import model.guardedBy
import model.InclusionCondition
import model.merge
import viaduct.graphql.schema.ViaductSchema

/** Grounds top-level keys and coalesces selections whose keys become equal. */
fun ObjectSelectionForest.instantiateBindings(operation: SharedOperationContext<*>): ObjectSelectionForest =
    groundSelections { selection ->
        selection.key.arguments.instantiateBindings(operation, selection.key.field)
    }

/** Awaits top-level key bindings and coalesces selections whose keys become equal. */
suspend fun ObjectSelectionForest.fetchBindings(operation: SharedOperationContext<*>): ObjectSelectionForest =
    groundSelections { selection ->
        selection.key.arguments.fetchBindings(operation, selection.key.field)
    }

/** Specializes this demand to [type] and grounds its top-level keys. */
fun SelectionForest.applicableGroundSelections(
    operation: SharedOperationContext<*>,
    type: ViaductSchema.Object,
): ObjectSelectionForest = merge(type).instantiateBindings(operation)

private inline fun ObjectSelectionForest.groundSelections(
    groundArguments: (ObjectSelection) -> Arguments.Ground,
): ObjectSelectionForest {
    val selectionsByKey =
        buildMap<ObjectEngineResult.GroundKey, MutableList<ObjectSelection>> {
            byKey().values.forEach { selection ->
                val key =
                    ObjectEngineResult.GroundKey.of(
                        field = selection.key.field,
                        arguments = groundArguments(selection),
                    )
                getOrPut(key, ::mutableListOf).add(selection)
            }
        }
    return ObjectSelectionForest.of(
        type = type,
        selections =
            selectionsByKey.map { (key, selections) ->
                ObjectSelection.of(
                    key = key,
                    possibleTypes = setOf(type),
                    inclusionCondition =
                        InclusionCondition.anyOf(
                            selections.map { selection -> selection.inclusionCondition },
                        ),
                    subselections =
                        selections
                            .map { selection ->
                                selection.subselections.guardedBy(selection.inclusionCondition)
                            }.concatenateSelectionForests(),
                )
            },
    )
}
