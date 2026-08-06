package model

import model.registry.ResolverRegistry

/**
 * The fixed schema and field resolvers under which model values and operations are interpreted.
 *
 * Equality is undefined for assumptions. Exactly one value is fixed for a reasoning exercise, and
 * every schema definition referenced by its model values belongs to [schema].
 */
sealed interface Assumptions {
    val schema: Schema
    val resolverRegistry: ResolverRegistry

    /** Whether selective output traversal rejects fields outside its supplied selections. */
    val selectiveResolvers: Boolean

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
    override fun behavioral(field: Schema.ObjectField): Boolean {
        val containingType = field.containingType
        require(schema.field(containingType.typeName, field.fieldName) == field) {
            "${containingType.typeName}/${field.fieldName} is not canonical in this world"
        }
        return field.fieldName == "__typename" ||
            field in resolverRegistry
    }
}
