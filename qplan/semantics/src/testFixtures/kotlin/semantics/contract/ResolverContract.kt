package semantics.contract

import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.Schema
import model.SelectionForest
import model.Value

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
        root: Value.Object,
        selections: SelectionForest,
    ): ObjectEngineResult

    fun observeResolution(
        world: Assumptions,
        root: Value.Object,
        selections: SelectionForest,
    ): ResolverResolutionObservation =
        ResultOnlyResolverResolutionObservation(
            resolve(world, root, selections),
        )

    fun expectedPassiveResultFieldNames(vararg fieldNames: String): Set<String> =
        fieldNames.filterNotTo(linkedSetOf()) { it == "__typename" } +
            if (selectiveResolvers) emptySet() else setOf("__typename")

    fun expectedPassiveResultKeys(
        type: Schema.ObjectType,
        keys: Set<Value.GroundKey>,
    ): Set<Value.GroundKey> =
        keys.filterNotTo(linkedSetOf()) { key ->
            key.field.fieldName == "__typename"
        } +
            if (selectiveResolvers) {
                emptySet()
            } else {
                setOf(
                    Value.GroundKey.of(
                        field = type.fields.getValue("__typename"),
                        arguments = emptyMap(),
                    ),
                )
            }
}

internal fun Value.Object.hasExactlyFields(
    vararg expectedFields: Value.GroundKey,
): Boolean = hasExactlyFields(expectedFields.toSet())

internal fun Value.Object.hasExactlyFields(
    expectedFields: Set<Value.GroundKey>,
): Boolean = fieldValues.keys == expectedFields
