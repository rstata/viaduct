package semantics.contract

import viaduct.engine.api.EngineObjectData
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.Schema
import model.requireField
import model.SelectionForest

/** Subject-specific evidence retained alongside one resolution result. */
interface ResolverResolutionObservation {
    val result: ObjectEngineResult
}

private data class ResultOnlyResolverResolutionObservation(
    override val result: ObjectEngineResult,
) : ResolverResolutionObservation

/**
 * A reusable contract subject for one field-resolution strategy.
 */
interface ResolverContract {
    val selectiveResolvers: Boolean
        get() = true

    fun resolve(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult

    fun observeResolution(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ResolverResolutionObservation =
        ResultOnlyResolverResolutionObservation(
            resolve(world, root, selections),
        )

    fun expectedPassiveResultFieldNames(vararg fieldNames: String): Set<String> =
        fieldNames.toSet()

    fun expectedPassiveResultKeys(
        @Suppress("UNUSED_PARAMETER")
        type: Schema.Object,
        keys: Set<ObjectEngineResult.GroundKey>,
    ): Set<ObjectEngineResult.GroundKey> = keys
}

internal fun EngineObjectData.Sync.hasExactlyFields(
    vararg expectedFields: ObjectEngineResult.GroundKey,
): Boolean = hasExactlyFields(expectedFields.toSet())

internal fun EngineObjectData.Sync.hasExactlyFields(
    expectedFields: Set<ObjectEngineResult.GroundKey>,
): Boolean =
    getSelections().toSet() ==
        expectedFields.mapTo(linkedSetOf()) { key -> key.field.name }
