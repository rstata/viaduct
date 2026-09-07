package semantics.resolver26

import model.InclusionCondition
import model.ObjectEngineResult
import model.Selection
import model.SelectionForest
import model.guardedBy
import model.registry.InstantiatedFieldPathDefinition
import model.selectionForestOf

/** Retains one provider path while removing conditions that could depend on its own value. */
private fun SelectionForest.providerPathDemand(
    path: List<ObjectEngineResult.Key>,
): SelectionForest {
    val key = path.firstOrNull() ?: return selectionForestOf()
    val remaining = path.drop(1)
    return flatMap { selection ->
        if (selection.key != key) {
            selectionForestOf()
        } else {
            val subselections =
                if (remaining.isEmpty()) {
                    selectionForestOf()
                } else {
                    selection.subselections.providerPathDemand(remaining)
                }
            if (remaining.isNotEmpty() && subselections.isEmpty()) {
                selectionForestOf()
            } else {
                selectionForestOf(
                    Selection.of(
                        key = selection.key,
                        possibleTypes = selection.possibleTypes,
                        subselections = subselections,
                        inclusionCondition = InclusionCondition.Always,
                    ),
                )
            }
        }
    }
}

/** Builds the hidden construction demand needed to complete every path-variable binding. */
internal fun SelectionForest.providerDemand(
    definitions: List<InstantiatedFieldPathDefinition>,
    inclusionCondition: InclusionCondition,
): SelectionForest =
    definitions.fold(selectionForestOf()) { demand, definition ->
        demand + providerPathDemand(definition.path).guardedBy(inclusionCondition)
    }
