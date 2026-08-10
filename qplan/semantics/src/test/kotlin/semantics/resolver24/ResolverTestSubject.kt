package semantics.resolver24

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import semantics.contract.validateObjectPathBindings

internal fun resolveWithBindingValidation(
    world: Assumptions,
    root: Value.Object,
    selections: SelectionForest,
): EngineResult.Object {
    val result =
        context(world) {
            root.resolve(selections)
        }
    context(world) {
        result.validateObjectPathBindings()
    }
    return result
}
