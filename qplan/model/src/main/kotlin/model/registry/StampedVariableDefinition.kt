package model.registry

import model.ObjectEngineResult
import model.Value

/**
 * One occurrence-specific variable and its occurrence-specific object provider path.
 *
 * Equality is structural: two definitions are equal exactly when their [variable] and [path] are
 * equal.
 */
sealed interface StampedObjectPathDefinition {
    val variable: Value.Variable
    val path: List<ObjectEngineResult.Key>

    companion object {
        /** Returns an object-path definition for the stamped [variable]. */
        fun of(
            variable: Value.Variable,
            path: List<ObjectEngineResult.Key>,
        ): StampedObjectPathDefinition {
            require(variable.isStamped) {
                "An object-path definition requires a stamped variable"
            }
            return StampedObjectPathDefinitionImpl(variable, path)
        }
    }
}

/**
 * One selection-specific variable use and the resolver definition that supplies its value.
 *
 * Equality is structural: two definitions are equal exactly when their [variable] and [definition]
 * are equal.
 */
sealed interface SelectionStampedVariableDefinition {
    val variable: Value.Variable
    val definition: VariableDefinition

    companion object {
        /** Returns a definition for a variable stamped at one selection occurrence. */
        fun of(
            variable: Value.Variable,
            definition: VariableDefinition,
        ): SelectionStampedVariableDefinition {
            require(variable.stamp?.occurrenceLineage?.isNotEmpty() == true) {
                "A selection-stamped definition requires a selection-stamped variable"
            }
            return SelectionStampedVariableDefinitionImpl(variable, definition)
        }
    }
}

private data class StampedObjectPathDefinitionImpl(
    override val variable: Value.Variable,
    override val path: List<ObjectEngineResult.Key>,
) : StampedObjectPathDefinition

private data class SelectionStampedVariableDefinitionImpl(
    override val variable: Value.Variable,
    override val definition: VariableDefinition,
) : SelectionStampedVariableDefinition
