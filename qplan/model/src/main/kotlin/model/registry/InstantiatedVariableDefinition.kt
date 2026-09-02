package model.registry

import model.Arguments

import model.ObjectEngineResult

/**
 * One variable instance and its occurrence-specific provider path.
 *
 * Equality is structural: two definitions are equal exactly when their [variable] and [path] are
 * equal.
 */
sealed interface InstantiatedFieldPathDefinition {
    val variable: Arguments.Variable
    val providerFragment: ProviderFragment
    val path: List<ObjectEngineResult.Key>

    companion object {
        /** Returns a from-field definition for the instantiated [variable]. */
        fun of(
            variable: Arguments.Variable,
            providerFragment: ProviderFragment,
            path: List<ObjectEngineResult.Key>,
        ): InstantiatedFieldPathDefinition {
            require(variable.isInstantiated) {
                "A from-field path definition requires an instantiated variable"
            }
            return InstantiatedFieldPathDefinitionImpl(
                variable = variable,
                providerFragment = providerFragment,
                path = path.toList(),
            )
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

private data class InstantiatedFieldPathDefinitionImpl(
    override val variable: Arguments.Variable,
    override val providerFragment: ProviderFragment,
    override val path: List<ObjectEngineResult.Key>,
) : InstantiatedFieldPathDefinition

private data class VariableInstanceDefinitionImpl(
    override val variable: Arguments.Variable,
    override val definition: VariableDefinition,
) : VariableInstanceDefinition
