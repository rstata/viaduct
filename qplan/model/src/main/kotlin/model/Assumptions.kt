package model

import model.registry.ResolverRegistry

/**
 * The schema, field resolvers, and monotonic variable bindings under which model values and
 * operations are interpreted.
 *
 * Equality is undefined for assumptions. Exactly one value is fixed for a reasoning exercise, and
 * every schema definition referenced by its model values belongs to [schema]. Each assumptions
 * value begins with no variable bindings.
 *
 * ### Invariant: assumptions-monotonic-variable-bindings
 *
 * The binding domain grows only through [bind]. Every bound variable has exactly one ground
 * [Value.Input] value, including a possible null value, and that value never changes. [binding] is
 * defined exactly on this domain.
 */
sealed interface Assumptions {
    val schema: Schema
    val resolverRegistry: ResolverRegistry

    /** Whether selective output traversal rejects fields outside its supplied selections. */
    val selectiveResolvers: Boolean

    /** Whether [variable] has a binding, including a binding whose value is null. */
    fun isBound(variable: Value.Variable.Stamped): Boolean

    /**
     * Returns the value bound to [variable].
     *
     * @throws IllegalStateException when [variable] is unbound
     */
    fun binding(variable: Value.Variable.Stamped): Value.Input?

    /**
     * Adds the first and only binding for [variable].
     *
     * [value] may be null. Its ground type excludes [Value.Variable] by construction.
     *
     * @throws IllegalStateException when [variable] is already bound
     */
    fun bind(
        variable: Value.Variable.Stamped,
        value: Value.Input?,
    )

    /**
     * Whether resolution of [field] crosses a resolver behavior boundary.
     *
     * This function is defined only for a canonical field on a concrete object type and is true
     * exactly for engine-supplied `__typename` or a registered field resolver. Synthetic fixture
     * bridges have no implicit special status.
     */
    fun behavioral(field: Schema.ObjectField): Boolean

    companion object {
        fun of(
            schema: Schema,
            resolverRegistry: ResolverRegistry,
            selectiveResolvers: Boolean = true,
        ): Assumptions =
            AssumptionsImpl(
                schema,
                resolverRegistry,
                selectiveResolvers,
            )
    }
}

private class AssumptionsImpl(
    override val schema: Schema,
    override val resolverRegistry: ResolverRegistry,
    override val selectiveResolvers: Boolean,
) : Assumptions {
    private val bindings = mutableMapOf<Value.Variable.Stamped, Value.Input?>()

    override fun isBound(variable: Value.Variable.Stamped): Boolean =
        variable in bindings

    override fun binding(variable: Value.Variable.Stamped): Value.Input? {
        check(isBound(variable)) {
            "Variable $variable is unbound"
        }
        return bindings[variable]
    }

    override fun bind(
        variable: Value.Variable.Stamped,
        value: Value.Input?,
    ) {
        check(!isBound(variable)) {
            "Variable $variable is already bound"
        }
        bindings[variable] = value
    }

    override fun behavioral(field: Schema.ObjectField): Boolean {
        val containingType = field.containingType
        require(schema.field(containingType.typeName, field.fieldName) == field) {
            "${containingType.typeName}/${field.fieldName} is not canonical in this world"
        }
        return field.fieldName == "__typename" ||
            field in resolverRegistry
    }
}
