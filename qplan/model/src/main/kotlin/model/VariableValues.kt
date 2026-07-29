package model

/**
 * Variable bindings that distinguish an unbound variable from one bound to GraphQL null.
 *
 * Every non-null binding is an input value containing no [Value.Variable].
 */
sealed interface VariableBindings : Map<String, Value.Input?> {
    /** @throws MissingVariablesException when [key] is unbound */
    override operator fun get(key: String): Value.Input?

    /** @throws MissingVariablesException when [key] is unbound */
    fun getValue(key: String): Value.Input?

    /** Replaces bound variables recursively while preserving unbound variables. */
    fun instantiateVariables(value: Value): Value?

    /**
     * Replaces all variables recursively.
     *
     * @throws MissingVariablesException after traversal when any variables are unbound
     */
    fun instantiateAllVariables(value: Value): Value?

    companion object {
        internal fun from(bindings: Map<String, Value?>): VariableBindings {
            val validatedBindings =
                bindings.mapValues { (variableName, value) ->
                    require(value == null || value is Value.Input) {
                        "Variable $variableName contains a non-input GraphQL value"
                    }
                    value as Value.Input?
                }
            val noBindings = VariableBindingsImpl(emptyMap())
            val unboundVariables =
                validatedBindings.values
                    .flatMap { noBindings.instantiate(it).unboundVariables }
                    .distinctBy { it.variableName }
            if (unboundVariables.isNotEmpty()) {
                throw MissingVariablesException(unboundVariables)
            }
            return VariableBindingsImpl(validatedBindings)
        }
    }
}

private class VariableBindingsImpl(
    private val backingMap: Map<String, Value.Input?>,
) : VariableBindings,
    Map<String, Value.Input?> by backingMap {
    override operator fun get(key: String): Value.Input? = getValue(key)

    override fun getValue(key: String): Value.Input? {
        if (!backingMap.containsKey(key)) {
            throw MissingVariablesException(listOf(Value.Variable.of(key)))
        }
        return backingMap[key]
    }

    override fun instantiateVariables(value: Value): Value? =
        instantiate(value).value

    override fun instantiateAllVariables(value: Value): Value? {
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

    fun instantiate(value: Value?): Instantiation {
        if (value == null || value == Value.Error) return Instantiation(value)
        return when (value) {
            is Value.Variable ->
                if (containsKey(value.variableName)) {
                    Instantiation(getValue(value.variableName))
                } else {
                    Instantiation(value, listOf(value))
                }
            is Value.InputList -> {
                val elements = value.values.map(::instantiate)
                Instantiation(
                    value =
                        Value.InputList.of(
                            typeExpr = value.typeExpr,
                            values = elements.map { it.value as Value.Input? },
                        ),
                    unboundVariables =
                        elements
                            .flatMap { it.unboundVariables }
                            .distinctBy { it.variableName },
                )
            }
            is Value.InputObject -> {
                val fields =
                    value.fieldValues.mapValues { (_, fieldValue) ->
                        instantiate(fieldValue)
                    }
                Instantiation(
                    value =
                        Value.InputObject.of(
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
    val value: Value?,
    val unboundVariables: List<Value.Variable> = emptyList(),
)

/**
 * One or more distinct variable values that are unbound under the current assumptions.
 */
class MissingVariablesException(
    variableValues: List<Value.Variable>,
) : NoSuchElementException(
        "Unbound variables: " + variableValues.joinToString { "$${it.variableName}" },
    ) {
    val variableValues: List<Value.Variable> = variableValues

    init {
        require(variableValues.isNotEmpty()) {
            "MissingVariablesException requires at least one variable"
        }
        require(variableValues.distinctBy { it.variableName }.size == variableValues.size) {
            "MissingVariablesException requires distinct variable names"
        }
    }
}
