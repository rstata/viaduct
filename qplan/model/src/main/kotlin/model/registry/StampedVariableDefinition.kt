package model.registry

import model.Arguments

import model.ObjectEngineResult

/**
 * One occurrence-specific variable and its occurrence-specific object provider path.
 *
 * Equality is structural: two definitions are equal exactly when their [variable] and [path] are
 * equal.
 */
sealed interface StampedObjectPathDefinition {
    val variable: Arguments.Variable
    val path: List<ObjectEngineResult.Key>

    companion object {
        /** Returns an object-path definition for the stamped [variable]. */
        fun of(
            variable: Arguments.Variable,
            path: List<ObjectEngineResult.Key>,
        ): StampedObjectPathDefinition {
            require(variable.isStamped) {
                "An object-path definition requires a stamped variable"
            }
            return StampedObjectPathDefinitionImpl(variable, path)
        }
    }
}

private data class StampedObjectPathDefinitionImpl(
    override val variable: Arguments.Variable,
    override val path: List<ObjectEngineResult.Key>,
) : StampedObjectPathDefinition
