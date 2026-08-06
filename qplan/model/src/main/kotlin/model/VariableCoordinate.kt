package model

/**
 * One globally named variable defined by the resolver at [field].
 *
 * Equality is structural over the canonical object field and variable name.
 */
sealed interface VariableCoordinate {
    val field: Schema.ObjectField
    val variable: Value.Variable

    companion object {
        /** Constructs the coordinate for [variable] owned by the resolver at [field]. */
        fun of(
            field: Schema.ObjectField,
            variable: Value.Variable,
        ): VariableCoordinate = VariableCoordinateImpl(field, variable)
    }
}

private data class VariableCoordinateImpl(
    override val field: Schema.ObjectField,
    override val variable: Value.Variable,
) : VariableCoordinate
