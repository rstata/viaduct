package model.registry

import model.Schema
import model.TypeExpr
import model.Value

/**
 * The source of one field-relative variable defined by a field resolver.
 *
 * Equality is structural: two definitions are equal exactly when they have the same variant and
 * equal [FromArgument.argument] or [FromObjectField.path], respectively.
 */
sealed interface VariableDefinition {
    /** A variable whose value is taken from an argument of its defining resolver field. */
    sealed interface FromArgument : VariableDefinition {
        val argument: Schema.FieldArgument

        companion object {
            /** Returns the definition that reads [argument]. */
            fun of(argument: Schema.FieldArgument): FromArgument =
                FromArgumentImpl(argument)
        }
    }

    /** A variable whose value is read from one path in its defining resolver's object fragment. */
    sealed interface FromObjectField : VariableDefinition {
        val path: List<Value.Key>

        companion object {
            /**
             * Returns the definition that reads [path].
             *
             * ### Invariant: from-object-field-variable-definition-path-shape
             *
             * [path] is nonempty, every nonterminal key selects a non-list composite value, and
             * the terminal key selects a simple value.
             */
            fun of(path: List<Value.Key>): FromObjectField {
                require(path.isNotEmpty()) {
                    "From-object-field variable path must not be empty"
                }
                path.dropLast(1).forEach { key ->
                    require(
                        key.field.typeExpr is TypeExpr.Named &&
                            key.field.typeExpr.baseType is Schema.CompositeType,
                    ) {
                        "From-object-field variable path cannot cross list or simple field " +
                            "${key.field.containingType.typeName}/${key.field.fieldName}"
                    }
                }
                val terminal = path.last().field
                require(terminal.typeExpr.baseType is Schema.SimpleType) {
                    "From-object-field variable path must end at a simple field, not " +
                        "${terminal.containingType.typeName}/${terminal.fieldName}"
                }
                return FromObjectFieldImpl(path)
            }
        }
    }
}

private data class FromArgumentImpl(
    override val argument: Schema.FieldArgument,
) : VariableDefinition.FromArgument

private data class FromObjectFieldImpl(
    override val path: List<Value.Key>,
) : VariableDefinition.FromObjectField
