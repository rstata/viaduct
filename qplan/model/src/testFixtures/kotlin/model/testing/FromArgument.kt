package model.testing

import viaduct.graphql.schema.ViaductSchema

import model.arg
import model.requireObjectField

/** One external `fromArgument` declaration accepted by test-fixture composition. */
class FromArgument private constructor(
    internal val argument: ViaductSchema.FieldArg,
) : VariableDeclaration {
    companion object {
        internal fun of(argument: ViaductSchema.FieldArg): FromArgument =
            FromArgument(argument)
    }
}

/** Defines a variable from [argumentName] of its defining resolver [field]. */
fun ViaductSchema.fromArgument(
    field: ViaductSchema.ObjectField,
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
