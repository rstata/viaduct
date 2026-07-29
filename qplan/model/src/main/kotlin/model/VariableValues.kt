package model

/**
 * Variable bindings that distinguish an unbound variable from one bound to GraphQL null.
 *
 * Every non-null binding is an input value containing no [Schema.VariableValue].
 */
sealed interface VariableBindings : Map<String, Schema.InputValue?> {
    /** @throws MissingVariablesException when [key] is unbound */
    override operator fun get(key: String): Schema.InputValue?

    /** @throws MissingVariablesException when [key] is unbound */
    fun getValue(key: String): Schema.InputValue?

    /** Replaces bound variables recursively while preserving unbound variables. */
    fun instantiateVariables(value: Schema.Value): Schema.Value?

    /**
     * Replaces all variables recursively.
     *
     * @throws MissingVariablesException after traversal when any variables are unbound
     */
    fun instantiateAllVariables(value: Schema.Value): Schema.Value?

    companion object {
        internal fun from(
            schema: Schema,
            bindings: Map<String, Schema.Value?>,
        ): VariableBindings {
            val validatedBindings =
                bindings.mapValues { (variableName, value) ->
                    require(value == null || value is Schema.InputValue) {
                        "Variable $variableName contains a non-input GraphQL value"
                    }
                    value as Schema.InputValue?
                }
            val noBindings = VariableBindingsImpl(schema, emptyMap())
            val unboundVariables =
                validatedBindings.values
                    .flatMap { noBindings.instantiate(it).unboundVariables }
                    .distinctBy { it.variableName }
            if (unboundVariables.isNotEmpty()) {
                throw MissingVariablesException(unboundVariables)
            }
            return VariableBindingsImpl(schema, validatedBindings)
        }
    }
}

private class VariableBindingsImpl(
    private val schema: Schema,
    private val backingMap: Map<String, Schema.InputValue?>,
) : VariableBindings,
    Map<String, Schema.InputValue?> by backingMap {
    override operator fun get(key: String): Schema.InputValue? = getValue(key)

    override fun getValue(key: String): Schema.InputValue? {
        if (!backingMap.containsKey(key)) {
            throw MissingVariablesException(listOf(Schema.VariableValue.of(key)))
        }
        return backingMap[key]
    }

    override fun instantiateVariables(value: Schema.Value): Schema.Value? =
        instantiate(value).value

    override fun instantiateAllVariables(value: Schema.Value): Schema.Value? {
        val result = instantiate(value)
        if (result.unboundVariables.isNotEmpty()) {
            throw MissingVariablesException(result.unboundVariables)
        }
        return result.value
    }

    override fun equals(other: Any?): Boolean =
        other is VariableBindings && entries == other.entries

    override fun hashCode(): Int = backingMap.hashCode()

    override fun toString(): String = backingMap.toString()

    fun instantiate(value: Schema.Value?): Instantiation {
        if (value == null || value == Schema.ErrorValue) return Instantiation(value)
        return when (value) {
            is Schema.VariableValue ->
                if (containsKey(value.variableName)) {
                    Instantiation(getValue(value.variableName))
                } else {
                    Instantiation(value, listOf(value))
                }
            is Schema.InputListValue -> {
                val elements = value.values.map(::instantiate)
                Instantiation(
                    value =
                        Schema.InputListValue.of(
                            typeExpr = value.typeExpr,
                            values = elements.map { it.value as Schema.InputValue? },
                        ),
                    unboundVariables =
                        elements
                            .flatMap { it.unboundVariables }
                            .distinctBy { it.variableName },
                )
            }
            is Schema.InputObjectValue -> {
                val fields =
                    value.fieldValues.mapValues { (_, fieldValue) ->
                        instantiate(fieldValue)
                    }
                Instantiation(
                    value =
                        Schema.InputObjectValue.of(
                            type = value.type,
                            fields = fields.mapValues { (_, result) -> result.value },
                        ),
                    unboundVariables =
                        fields.values
                            .flatMap { it.unboundVariables }
                            .distinctBy { it.variableName },
                )
            }
            else -> Instantiation(value)
        }
    }
}

private data class Instantiation(
    val value: Schema.Value?,
    val unboundVariables: List<Schema.VariableValue> = emptyList(),
)

/**
 * One or more distinct variable values that are unbound under the current assumptions.
 */
class MissingVariablesException(
    variableValues: List<Schema.VariableValue>,
) : NoSuchElementException(
        "Unbound variables: " + variableValues.joinToString { "$${it.variableName}" },
    ) {
    val variableValues: List<Schema.VariableValue> = variableValues

    init {
        require(variableValues.isNotEmpty()) {
            "MissingVariablesException requires at least one variable"
        }
        require(variableValues.distinctBy { it.variableName }.size == variableValues.size) {
            "MissingVariablesException requires distinct variable names"
        }
    }
}
