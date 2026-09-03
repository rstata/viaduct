package semantics.shared

import model.Arguments
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.SelectionForest
import model.concatenateSelectionForests
import model.merge
import viaduct.graphql.schema.ViaductSchema

/** Grounds top-level keys and coalesces selections whose keys become equal. */
context(operation: OperationContext)
fun ObjectSelectionForest.instantiateBindings(): ObjectSelectionForest =
    groundSelections { selection ->
        selection.key.arguments.instantiateBindings(selection.key.field)
    }

/** Awaits top-level key bindings and coalesces selections whose keys become equal. */
context(operation: OperationContext)
suspend fun ObjectSelectionForest.fetchBindings(): ObjectSelectionForest =
    groundSelections { selection ->
        selection.key.arguments.fetchBindings(selection.key.field)
    }

/** Specializes this demand to [type] and grounds its top-level keys. */
context(operation: OperationContext)
fun SelectionForest.applicableGroundSelections(
    type: ViaductSchema.Object,
): ObjectSelectionForest = merge(type).instantiateBindings()

private inline fun ObjectSelectionForest.groundSelections(
    groundArguments: (ObjectSelection) -> Arguments.Ground,
): ObjectSelectionForest {
    val childrenByKey =
        buildMap<ObjectEngineResult.GroundKey, MutableList<SelectionForest>> {
            byKey().values.forEach { selection ->
                val key =
                    ObjectEngineResult.GroundKey.of(
                        field = selection.key.field,
                        arguments = groundArguments(selection),
                    )
                getOrPut(key, ::mutableListOf).add(selection.subselections)
            }
        }
    return ObjectSelectionForest.of(
        type = type,
        selections =
            childrenByKey.map { (key, children) ->
                ObjectSelection.of(
                    key = key,
                    possibleTypes = setOf(type),
                    subselections = children.concatenateSelectionForests(),
                )
            },
    )
}
