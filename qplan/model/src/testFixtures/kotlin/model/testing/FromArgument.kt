package model.testing

import model.Schema

/** One external `fromArgument` declaration accepted by test-fixture composition. */
class FromArgument private constructor(
    internal val argument: Schema.FieldArgument,
) : VariableDeclaration {
    companion object {
        internal fun of(argument: Schema.FieldArgument): FromArgument =
            FromArgument(argument)
    }
}

/** Defines a variable from [argumentName] of its defining resolver [field]. */
fun Schema.fromArgument(
    field: Schema.ObjectField,
    argumentName: String,
): FromArgument {
    require(objectField(field.containingType.typeName, field.fieldName) == field) {
        "${field.containingType.typeName}/${field.fieldName} is not canonical in this schema"
    }
    val argument =
        field.arguments.fields[argumentName] as? Schema.FieldArgument
            ?: throw IllegalArgumentException(
                "${field.containingType.typeName}/${field.fieldName} has no argument $argumentName",
            )
    return FromArgument.of(argument)
}
