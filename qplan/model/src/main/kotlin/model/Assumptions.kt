package model

import model.registry.ResolverRegistry
import viaduct.graphql.schema.ViaductSchema
import java.util.concurrent.ConcurrentHashMap

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
 * The binding domain grows only through [declareBinding] or [bindVariable]. Every declared variable
 * instance has exactly one [Promise] of a [VariableBinding]. Each promise completes once, and
 * [getBinding] and [fetchBinding] are defined exactly on declared bindings.
 */
sealed interface Assumptions {
    val schema: ViaductSchema
    val resolverRegistry: ResolverRegistry

    /** Whether resolver invocation and passive output traversal are selective to supplied demand. */
    val selectiveResolvers: Boolean

    /** Query-fragment result witnesses keyed by the exact resolver occurrence path. */
    val queryValues: ConcurrentHashMap<List<PathComponent>, ObjectEngineResult>

    /** Whether [variableId] has a completed binding, including one whose value is null. */
    fun isBound(variableId: VariableInstanceId): Boolean

    /**
     * Declares the uncompleted binding promise for [variableId].
     *
     * @throws IllegalStateException when [variable] has already been declared
     */
    fun declareBinding(variableId: VariableInstanceId)

    /**
     * Binds [variableId] immediately to [binding].
     *
     * @throws IllegalStateException when [variable] has already been declared or bound
     */
    fun bindVariable(
        variableId: VariableInstanceId,
        binding: VariableBinding,
    )

    fun bindVariable(
        variableId: VariableInstanceId,
        value: EngineInputData?,
    ) = bindVariable(variableId, VariableBinding.of(value))

    /**
     * Completes the declared binding for [variableId] with [binding].
     *
     * @throws IllegalStateException when [variable] is undeclared or already completed
     */
    fun completeBinding(
        variableId: VariableInstanceId,
        binding: VariableBinding,
    )

    fun completeBinding(
        variableId: VariableInstanceId,
        value: EngineInputData?,
    ) = completeBinding(variableId, VariableBinding.of(value))

    /**
     * Returns the completed value bound to [variable] without suspending.
     *
     * @throws IllegalStateException when [variable] is undeclared
     * @throws UncompletedPromiseException when its binding is incomplete
     */
    fun getBinding(variableId: VariableInstanceId): VariableBinding

    /**
     * Returns the value bound to [variable], suspending until its declared promise completes.
     *
     * @throws IllegalStateException when [variable] is undeclared
     */
    suspend fun fetchBinding(variableId: VariableInstanceId): VariableBinding

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
    override val queryValues =
        ConcurrentHashMap<List<PathComponent>, ObjectEngineResult>()

    private val bindings =
        OnceStore<VariableInstanceId, Promise<VariableBinding>>()

    override fun isBound(variableId: VariableInstanceId): Boolean =
        bindings.isSet(variableId) && bindings.read(variableId).isCompleted

    override fun declareBinding(variableId: VariableInstanceId) {
        bindings.write(variableId, Promise.ofDeferred())
    }

    override fun bindVariable(
        variableId: VariableInstanceId,
        binding: VariableBinding,
    ) {
        bindings.write(variableId, Promise.of(binding))
    }

    override fun completeBinding(
        variableId: VariableInstanceId,
        binding: VariableBinding,
    ) {
        bindingPromise(variableId).complete(binding)
    }

    override fun getBinding(variableId: VariableInstanceId): VariableBinding =
        bindingPromise(variableId).get()

    override suspend fun fetchBinding(variableId: VariableInstanceId): VariableBinding =
        bindingPromise(variableId).await()

    private fun bindingPromise(
        variableId: VariableInstanceId,
    ): Promise<VariableBinding> = bindings.read(variableId)
}
