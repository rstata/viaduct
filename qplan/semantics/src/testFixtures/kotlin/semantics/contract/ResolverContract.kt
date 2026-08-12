package semantics.contract

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value

/** Subject-specific evidence retained alongside one resolution result. */
interface ResolverResolutionObservation {
    val result: EngineResult.Object
}

private data class ResultOnlyResolverResolutionObservation(
    override val result: EngineResult.Object,
) : ResolverResolutionObservation

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

    fun observeResolution(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): ResolverResolutionObservation =
        ResultOnlyResolverResolutionObservation(
            resolve(world, root, selections),
        )
}
