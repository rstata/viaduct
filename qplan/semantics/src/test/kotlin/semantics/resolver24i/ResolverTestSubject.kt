package semantics.resolver24i

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value

internal fun resolveSubject(
    world: Assumptions,
    root: Value.Object,
    selections: SelectionForest,
): EngineResult.Object =
    context(world) {
        root.resolve(selections)
    }
