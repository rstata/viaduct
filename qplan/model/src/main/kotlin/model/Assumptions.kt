package model

import model.registry.ResolverRegistry
import viaduct.graphql.schema.ViaductSchema

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
 * The binding domain grows only through [declareBinding] or [bindVariable]. Every declared stamped
 * variable has exactly one [Promise] of a [VariableBinding]. Each promise completes once, and
 * [getBinding] and [fetchBinding] are defined exactly on declared bindings.
 */
sealed interface Assumptions {
    val schema: ViaductSchema
    val resolverRegistry: ResolverRegistry

    /** Whether resolver invocation and passive output traversal are selective to supplied demand. */
    val selectiveResolvers: Boolean

    /** Whether [variable] has a completed binding, including a binding whose value is null. */
    fun isBound(variable: Arguments.Variable): Boolean

    /**
     * Declares the uncompleted binding promise for [variable].
     *
     * @throws IllegalStateException when [variable] has already been declared
     */
    fun declareBinding(variable: Arguments.Variable)

    /**
     * Binds [variable] immediately to [binding].
     *
     * @throws IllegalStateException when [variable] has already been declared or bound
     */
    fun bindVariable(
        variable: Arguments.Variable,
        binding: VariableBinding,
    )

    fun bindVariable(
        variable: Arguments.Variable,
        value: EngineInputData?,
    ) = bindVariable(variable, VariableBinding.of(value))

    /**
     * Completes the declared binding for [variable] with [binding].
     *
     * @throws IllegalStateException when [variable] is undeclared or already completed
     */
    fun completeBinding(
        variable: Arguments.Variable,
        binding: VariableBinding,
    )

    fun completeBinding(
        variable: Arguments.Variable,
        value: EngineInputData?,
    ) = completeBinding(variable, VariableBinding.of(value))

    /**
     * Returns the completed value bound to [variable] without suspending.
     *
     * @throws IllegalStateException when [variable] is undeclared
     * @throws UncompletedPromiseException when its binding is incomplete
     */
    fun getBinding(variable: Arguments.Variable): VariableBinding

    /**
     * Returns the value bound to [variable], suspending until its declared promise completes.
     *
     * @throws IllegalStateException when [variable] is undeclared
     */
    suspend fun fetchBinding(variable: Arguments.Variable): VariableBinding

    companion object {
        fun of(
            schema: ViaductSchema,
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
    override val schema: ViaductSchema,
    override val resolverRegistry: ResolverRegistry,
    override val selectiveResolvers: Boolean,
) : Assumptions {
    private val bindings =
        OnceStore<Arguments.Variable, Promise<VariableBinding>>()

    override fun isBound(variable: Arguments.Variable): Boolean {
        require(variable.isStamped) { "Variable templates cannot have bindings" }
        return bindings.isSet(variable) && bindings.read(variable).isCompleted
    }

    override fun declareBinding(variable: Arguments.Variable) {
        require(variable.isStamped) { "Variable templates cannot have bindings" }
        bindings.write(variable, Promise.ofDeferred())
    }

    override fun bindVariable(
        variable: Arguments.Variable,
        binding: VariableBinding,
    ) {
        require(variable.isStamped) { "Variable templates cannot have bindings" }
        bindings.write(variable, Promise.of(binding))
    }

    override fun completeBinding(
        variable: Arguments.Variable,
        binding: VariableBinding,
    ) {
        require(variable.isStamped) { "Variable templates cannot have bindings" }
        bindingPromise(variable).complete(binding)
    }

    override fun getBinding(variable: Arguments.Variable): VariableBinding {
        require(variable.isStamped) { "Variable templates cannot have bindings" }
        return bindingPromise(variable).get()
    }

    override suspend fun fetchBinding(variable: Arguments.Variable): VariableBinding {
        require(variable.isStamped) { "Variable templates cannot have bindings" }
        return bindingPromise(variable).await()
    }

    private fun bindingPromise(
        variable: Arguments.Variable,
    ): Promise<VariableBinding> = bindings.read(variable)
}
