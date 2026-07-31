package model

import model.registry.ExecutorRegistry

/**
 * The fixed schema, bindings, and executors under which model values and operations are interpreted.
 *
 * Equality is undefined for assumptions. Exactly one value is fixed for a reasoning exercise, and
 * every schema definition referenced by its model values belongs to [schema].
 */
sealed interface Assumptions {
    val schema: Schema
    val variableValues: VariableBindings
    val executorRegistry: ExecutorRegistry

    /**
     * Whether resolver02 deliberately stops resolver-demand closure after one expansion.
     *
     * This fault-injection flag exists to keep an evergreen control showing that generated
     * properties reject an implementation without transitive demand closure. It is false in every
     * ordinary reasoning world.
     */
    val noTransitiveDemand: Boolean

    /**
     * Whether resolution of [field] crosses a resolver behavior boundary.
     *
     * This function is defined only for a canonical field on a concrete object type and is true
     * exactly for engine-supplied `__typename` or a registered field resolver. Synthetic fixture
     * bridges have no implicit special status.
     */
    fun behavioral(field: Schema.OutputField): Boolean

    companion object {
        fun of(
            schema: Schema,
            bindings: Map<String, Value?>,
            executorRegistry: ExecutorRegistry,
            noTransitiveDemand: Boolean = false,
        ): Assumptions =
            AssumptionsImpl(
                schema,
                bindings,
                executorRegistry,
                noTransitiveDemand,
            )
    }
}

private class AssumptionsImpl(
    override val schema: Schema,
    bindings: Map<String, Value?>,
    override val executorRegistry: ExecutorRegistry,
    override val noTransitiveDemand: Boolean,
) : Assumptions {
    override val variableValues: VariableBindings =
        VariableBindings.from(bindings)

    override fun behavioral(field: Schema.OutputField): Boolean {
        val containingType = field.containingType
        require(containingType is Schema.ObjectType) {
            "Behavioral is defined only for fields on concrete object types"
        }
        require(schema.field(containingType.typeName, field.fieldName) == field) {
            "${containingType.typeName}/${field.fieldName} is not canonical in this world"
        }
        return field.fieldName == "__typename" ||
            field in executorRegistry
    }
}
