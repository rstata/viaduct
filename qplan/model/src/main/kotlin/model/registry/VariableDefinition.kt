package model.registry

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult


/**
 * The source of one field-relative variable defined by a field resolver.
 *
 * Equality is structural: two definitions are equal exactly when they have the same variant and
 * equal [FromArgument.argument] or [FromObjectField.path], respectively.
 */
sealed interface VariableDefinition {
    /** A variable whose value is taken from an argument of its defining resolver field. */
    sealed interface FromArgument : VariableDefinition {
        val argument: ViaductSchema.FieldArg

        companion object {
            /** Returns the definition that reads [argument]. */
            fun of(argument: ViaductSchema.FieldArg): FromArgument =
                FromArgumentImpl(argument)
        }
    }

    /** A variable whose value is read from one path in its defining resolver's object fragment. */
    sealed interface FromObjectField : VariableDefinition {
        val path: List<ObjectEngineResult.Key>

        companion object {
            /**
             * Returns the definition that reads [path].
             *
             * ### Invariant: from-object-field-variable-definition-path-shape
             *
             * [path] is nonempty, every nonterminal key selects a non-list composite value, and
             * the terminal key selects a simple value.
             */
            fun of(path: List<ObjectEngineResult.Key>): FromObjectField {
                require(path.isNotEmpty()) {
                    "From-object-field variable path must not be empty"
                }
                path.dropLast(1).forEach { key ->
                    require(
                        !key.field.type.isList &&
                            key.field.type.baseTypeDef is ViaductSchema.CompositeTypeDef,
                    ) {
                        "From-object-field variable path cannot cross list or simple field " +
                            "${key.field.containingDef.name}/${key.field.name}"
                    }
                }
                val terminal = path.last().field
                require(terminal.type.baseTypeDef is ViaductSchema.SimpleTypeDef) {
                    "From-object-field variable path must end at a simple field, not " +
                        "${terminal.containingDef.name}/${terminal.name}"
                }
                return FromObjectFieldImpl(path)
            }
        }
    }
}

private data class FromArgumentImpl(
    override val argument: ViaductSchema.FieldArg,
) : VariableDefinition.FromArgument

private data class FromObjectFieldImpl(
    override val path: List<ObjectEngineResult.Key>,
) : VariableDefinition.FromObjectField
