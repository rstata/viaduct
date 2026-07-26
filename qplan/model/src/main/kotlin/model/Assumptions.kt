package model

import model.registry.ExecutorRegistry
import model.spec.SpecSelection

/**
 * The fixed schema, bindings, and executors under which model values and operations are interpreted.
 *
 * Exactly one instance is fixed for a reasoning world. Concrete implementations use `@Singleton`
 * to record that modeling assumption for dependency injection; it does not make this a JVM-global
 * value.
 */
interface Assumptions {
    /**
     * The canonical schema for this reasoning world.
     *
     * Every schema definition referenced by a model value interpreted in this world belongs to this
     * schema and satisfies the canonicality invariants documented by [Schema].
     */
    val schema: Schema

    /**
     * The known variable bindings for this reasoning world.
     *
     * A bound value may be null, representing GraphQL null, but a non-null bound value cannot
     * recursively contain a [Schema.VariableValue]. Variable-to-variable bindings, including nested
     * references and cycles, are therefore excluded. A missing entry denotes an unbound or unknown
     * variable. A variable may be bound to [Schema.ErrorValue] because providers or fields may fail.
     *
     * See [Schema.VariableValue] for how bindings affect conservative equality.
     */
    val variableValues: VariableBindings

    /**
     * The node and field resolvers fixed for this reasoning world.
     */
    val executorRegistry: ExecutorRegistry

    /**
     * Parses and validates one GraphQL named fragment against [schema].
     *
     * The fragment name is ignored. The result contains its canonical composite type condition
     * followed by the post-validation selections in its selection set.
     */
    fun selectionsFrom(fragment: String): Pair<Schema.CompositeType, List<SpecSelection>>

    companion object {
        /**
         * Constructs one reasoning world over an already constructed [schema].
         *
         * Every value in [bindings] must have been constructed by [schema].
         */
        @JvmStatic
        fun of(
            schema: GJSchema,
            bindings: Map<String, Schema.Value?>,
            executorRegistry: ExecutorRegistry = ExecutorRegistry.empty(schema),
        ): Assumptions = DefaultAssumptions(schema, bindings, executorRegistry)
    }
}
