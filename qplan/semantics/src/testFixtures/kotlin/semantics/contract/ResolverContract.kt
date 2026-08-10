package semantics.contract

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value

/**
 * A reusable contract subject for one field-resolution strategy.
 */
interface ResolverContract {
    val selectiveResolvers: Boolean
        get() = true

    fun resolve(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): EngineResult.Object
}
