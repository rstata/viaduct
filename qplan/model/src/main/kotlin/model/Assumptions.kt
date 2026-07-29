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
     * Whether resolution of [field] crosses a resolver behavior boundary.
     *
     * This function is defined only for a canonical field on a concrete object type.
     */
    fun behavioral(field: Schema.OutputField): Boolean

    companion object {
        fun of(
            schema: Schema,
            bindings: Map<String, Value?>,
            executorRegistry: ExecutorRegistry,
        ): Assumptions = AssumptionsImpl(schema, bindings, executorRegistry)
    }
}

/** Returns [type]'s canonical argumentless `id` key when it has a node resolver. */
fun Assumptions.idKeyOf(type: Schema.ObjectType): Value.Key? =
    if (type in executorRegistry) {
        Value.Key.of(
            field = schema.field(type.typeName, "id"),
            arguments = emptyMap(),
        )
    } else {
        null
    }

private class AssumptionsImpl(
    override val schema: Schema,
    bindings: Map<String, Value?>,
    override val executorRegistry: ExecutorRegistry,
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
            field in executorRegistry ||
            (containingType in executorRegistry && field.fieldName != "id")
    }
}
