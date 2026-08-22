package model.registry

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.EngineInputData
import model.ObjectEngineResult
import model.inputType


/**
 * The source of one field-relative variable defined by a field resolver.
 *
 * Equality is structural: two definitions are equal exactly when they have the same variant and
 * equal [FromArgument.argument] and [FromArgument.inputPath], or [FromObjectField.path],
 * respectively.
 */
sealed interface VariableDefinition {
    /** A variable whose value is read from an input path rooted at one resolver argument. */
    sealed interface FromArgument : VariableDefinition {
        val argument: ViaductSchema.FieldArg
        val inputPath: List<ViaductSchema.Field>

        companion object {
            /**
             * Returns the definition that reads [argument] followed by [inputPath].
             *
             * ### Invariant: from-argument-variable-definition-path-shape
             *
             * Every path component is a canonical field of the preceding input-object type. The
             * path never traverses a list.
             */
            fun of(
                argument: ViaductSchema.FieldArg,
                inputPath: List<ViaductSchema.Field> = emptyList(),
            ): FromArgument {
                var currentType = argument.inputType
                inputPath.forEach { field ->
                    require(!currentType.isList) {
                        "From-argument variable path cannot traverse list type $currentType"
                    }
                    val inputObject = currentType.baseTypeDef as? ViaductSchema.Input
                    require(
                        inputObject != null &&
                            field.containingDef == inputObject &&
                            inputObject.field(field.name) == field,
                    ) {
                        "From-argument variable path field ${field.containingDef.name}/" +
                            "${field.name} does not belong to $currentType"
                    }
                    currentType = field.inputType
                }
                return FromArgumentImpl(argument, inputPath.toList())
            }
        }

        /** Reads this definition from one resolved argument tuple. */
        fun read(arguments: Arguments.Resolved): EngineInputData?
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
    override val inputPath: List<ViaductSchema.Field>,
) : VariableDefinition.FromArgument {
    override fun read(arguments: Arguments.Resolved): EngineInputData? =
        arguments.fieldValues.getValue(argument.name)
}

private data class FromObjectFieldImpl(
    override val path: List<ObjectEngineResult.Key>,
) : VariableDefinition.FromObjectField
