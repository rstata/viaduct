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
 * The binding domain grows only through [declareBinding]. Every declared variable has exactly one
 * [Promise] of a ground [Value.Input] value, including a possible null value. Each promise completes
 * once, and [getBinding] and [fetchBinding] are defined exactly on declared bindings.
 */
sealed interface Assumptions {
    val schema: Schema
    val resolverRegistry: ResolverRegistry

    /** Whether resolver invocation and passive output traversal are selective to supplied demand. */
    val selectiveResolvers: Boolean

    /** Whether [variable] has a completed binding, including a binding whose value is null. */
    fun isBound(variable: Value.Variable.Stamped): Boolean

    /**
     * Declares the uncompleted binding promise for [variable].
     *
     * @throws IllegalStateException when [variable] has already been declared
     */
    fun declareBinding(variable: Value.Variable.Stamped)

    /**
     * Completes the declared binding for [variable].
     *
     * [value] may be null. Its ground type excludes [Value.Variable] by construction.
     *
     * @throws IllegalStateException when [variable] is undeclared or already completed
     */
    fun completeBinding(
        variable: Value.Variable.Stamped,
        value: Value.Input?,
    )

    /**
     * Returns the completed value bound to [variable] without suspending.
     *
     * @throws IllegalStateException when [variable] is undeclared
     * @throws UncompletedPromiseException when its binding is incomplete
     */
    fun getBinding(variable: Value.Variable.Stamped): Value.Input?

    /**
     * Returns the value bound to [variable], suspending until its declared promise completes.
     *
     * @throws IllegalStateException when [variable] is undeclared
     */
    suspend fun fetchBinding(variable: Value.Variable.Stamped): Value.Input?

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
    private val bindings =
        OnceStore<Value.Variable.Stamped, Promise<Value.Input?>>()

    override fun isBound(variable: Value.Variable.Stamped): Boolean =
        bindings.isSet(variable) && bindings.read(variable).isCompleted

    override fun declareBinding(variable: Value.Variable.Stamped) {
        bindings.write(variable, Promise.ofDeferred())
    }

    override fun completeBinding(
        variable: Value.Variable.Stamped,
        value: Value.Input?,
    ) {
        bindingPromise(variable).complete(value)
    }

    override fun getBinding(variable: Value.Variable.Stamped): Value.Input? =
        bindingPromise(variable).get()

    override suspend fun fetchBinding(variable: Value.Variable.Stamped): Value.Input? =
        bindingPromise(variable).await()

    override fun behavioral(field: Schema.ObjectField): Boolean {
        val containingType = field.containingType
        require(schema.field(containingType.typeName, field.fieldName) == field) {
            "${containingType.typeName}/${field.fieldName} is not canonical in this world"
        }
        return field.fieldName == "__typename" ||
            field in resolverRegistry
    }

    private fun bindingPromise(
        variable: Value.Variable.Stamped,
    ): Promise<Value.Input?> = bindings.read(variable)
}
