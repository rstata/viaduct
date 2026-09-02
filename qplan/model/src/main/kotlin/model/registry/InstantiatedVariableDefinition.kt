package model.registry

import model.Arguments

import model.ObjectEngineResult

/**
 * One variable instance and its occurrence-specific object provider path.
 *
 * Equality is structural: two definitions are equal exactly when their [variable] and [path] are
 * equal.
 */
sealed interface InstantiatedObjectPathDefinition {
    val variable: Arguments.Variable
    val path: List<ObjectEngineResult.Key>

    companion object {
        /** Returns an object-path definition for the instantiated [variable]. */
        fun of(
            variable: Arguments.Variable,
            path: List<ObjectEngineResult.Key>,
        ): InstantiatedObjectPathDefinition {
            require(variable.isInstantiated) {
                "An object-path definition requires an instantiated variable"
            }
            return InstantiatedObjectPathDefinitionImpl(variable, path)
        }
    }
}

/**
 * One variable instance and its occurrence-specific Query provider path.
 *
 * Equality is structural: two definitions are equal exactly when their [variable] and [path] are
 * equal.
 */
sealed interface InstantiatedQueryPathDefinition {
    val variable: Arguments.Variable
    val path: List<ObjectEngineResult.Key>

    companion object {
        /** Returns a Query-path definition for the instantiated [variable]. */
        fun of(
            variable: Arguments.Variable,
            path: List<ObjectEngineResult.Key>,
        ): InstantiatedQueryPathDefinition {
            require(variable.isInstantiated) {
                "A Query-path definition requires an instantiated variable"
            }
            return InstantiatedQueryPathDefinitionImpl(variable, path)
        }
    }
}

/**
 * One resolver-application variable instance and the resolver definition that supplies its value.
 *
 * Equality is structural: two definitions are equal exactly when their [variable] and [definition]
 * are equal.
 */
sealed interface VariableInstanceDefinition {
    val variable: Arguments.Variable
    val definition: VariableDefinition

    companion object {
        /** Returns a definition for one instantiated variable. */
        fun of(
            variable: Arguments.Variable,
            definition: VariableDefinition,
        ): VariableInstanceDefinition {
            require(variable.isInstantiated) {
                "A variable-instance definition requires an instantiated variable"
            }
            return VariableInstanceDefinitionImpl(variable, definition)
        }
    }
}

private data class InstantiatedObjectPathDefinitionImpl(
    override val variable: Arguments.Variable,
    override val path: List<ObjectEngineResult.Key>,
) : InstantiatedObjectPathDefinition

private data class InstantiatedQueryPathDefinitionImpl(
    override val variable: Arguments.Variable,
    override val path: List<ObjectEngineResult.Key>,
) : InstantiatedQueryPathDefinition

private data class VariableInstanceDefinitionImpl(
    override val variable: Arguments.Variable,
    override val definition: VariableDefinition,
) : VariableInstanceDefinition
