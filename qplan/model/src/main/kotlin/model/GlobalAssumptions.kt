package model

import model.spec.SpecSelection

/**
 * The fixed inputs under which model values and operations over them are interpreted.
 *
 * These assumptions are global only within one reasoning world. They are not JVM globals, and
 * callers must not assume that this interface has a singleton implementation. An operation may
 * instead use a different immutable snapshot when it derives a new world.
 */
interface GlobalAssumptions {
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
     * recursively contain a [GraphQLVariableValue]. Variable-to-variable bindings, including nested
     * references and cycles, are therefore excluded. A missing entry denotes an unbound or unknown
     * variable. A variable may be bound to [GraphQLErrorValue] because providers or fields may fail.
     *
     * See [GraphQLVariableValue] for how bindings affect conservative equality.
     */
    val variableValues: VariableBindings

    /**
     * Parses and validates one GraphQL named fragment against [schema].
     *
     * The fragment name is ignored. The result contains its canonical composite type condition
     * followed by the post-validation selections in its selection set.
     */
    fun selectionsFrom(fragment: String): Pair<Schema.CompositeType, List<SpecSelection>>
}
