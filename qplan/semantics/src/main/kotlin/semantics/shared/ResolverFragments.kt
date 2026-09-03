package semantics.shared

import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.registry.FieldResolver

/** Returns this resolver's object fragment grounded at exact occurrence [path]. */
context(operation: OperationContext)
fun FieldResolver.objectFragmentAt(
    root: ObjectEngineResult,
    path: List<PathComponent>,
): ObjectSelectionForest =
    context(operation.world) {
        instantiateFragmentsAt(root, path)
            .objectFragment
            .constructionSelections
    }.applicableGroundSelections(field.containingDef)
