package model.testing

import model.Schema
import model.arg
import model.requireObjectField

/** One external `fromArgument` declaration accepted by test-fixture composition. */
class FromArgument private constructor(
    internal val argument: Schema.FieldArg,
) : VariableDeclaration {
    companion object {
        internal fun of(argument: Schema.FieldArg): FromArgument =
            FromArgument(argument)
    }
}

/** Defines a variable from [argumentName] of its defining resolver [field]. */
fun Schema.fromArgument(
    field: Schema.ObjectField,
    argumentName: String,
): FromArgument {
    require(requireObjectField(field.containingDef.name, field.name) == field) {
        "${field.containingDef.name}/${field.name} is not canonical in this schema"
    }
    val argument =
        field.arg(argumentName)
            ?: throw IllegalArgumentException(
                "${field.containingDef.name}/${field.name} has no argument $argumentName",
            )
    return FromArgument.of(argument)
}
