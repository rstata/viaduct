package semantics.contract

import model.Assumptions
import model.EngineResult
import model.Schema
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

    val alwaysGeneratesTypename: Boolean
        get() = false

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

    fun expectedResultFieldNames(vararg fieldNames: String): Set<String> =
        fieldNames.toSet() +
            if (alwaysGeneratesTypename) setOf("__typename") else emptySet()

    fun expectedResultKeys(
        type: Schema.ObjectType,
        keys: Set<Value.GroundKey>,
    ): Set<Value.GroundKey> =
        keys +
            if (alwaysGeneratesTypename) {
                setOf(
                    Value.GroundKey.of(
                        field = type.fields.getValue("__typename"),
                        arguments = emptyMap(),
                    ),
                )
            } else {
                emptySet()
            }
}

internal fun Value.Object.hasExactlyFields(
    vararg expectedFields: Value.GroundKey,
): Boolean = hasExactlyFields(expectedFields.toSet())

internal fun Value.Object.hasExactlyFields(
    expectedFields: Set<Value.GroundKey>,
): Boolean {
    val typenameKey =
        Value.GroundKey.of(
            field = type.fields.getValue("__typename"),
            arguments = emptyMap(),
        )
    return fieldValues.keys == expectedFields + typenameKey &&
        fieldValues.getValue(typenameKey) == Value.String.of(type.typeName)
}
