package model

/**
 * Variable bindings that distinguish an unbound variable from one bound to GraphQL null.
 *
 * Unlike an ordinary [Map], [get] and [getValue] throw [MissingVariablesException] when used on an
 * unbound variable. Use [containsKey] to determine whether a variable is bound.
 *
 * Assumptions construction takes a snapshot of the top-level map and recursively requires every
 * non-null value to be a [Schema.InputValue] containing no [Schema.VariableValue].
 * [Schema.ErrorValue] is permitted.
 *
 * [Map] extension functions such as [Map.getOrElse] may call [get] and therefore throw instead of
 * applying their fallback. They should not be used when the variable may be unbound.
 */
class VariableBindings private constructor(
    private val schema: Schema,
    private val backingMap: Map<String, Schema.InputValue?>,
) : Map<String, Schema.InputValue?> by backingMap {
    /** @throws MissingVariablesException when [key] is unbound */
    override operator fun get(key: String): Schema.InputValue? = getValue(key)

    /** @throws MissingVariablesException when [key] is unbound */
    fun getValue(key: String): Schema.InputValue? {
        if (!backingMap.containsKey(key)) {
            throw MissingVariablesException(
                listOf(schema.variableValue(key)),
            )
        }
        return backingMap[key]
    }

    /**
     * Recursively replaces every bound [Schema.VariableValue] in [value] with its binding.
     *
     * Unbound variables remain in the returned value. The result is null when [value] is a
     * variable bound to GraphQL null.
     */
    fun instantiateVariables(value: Schema.Value): Schema.Value? =
        instantiateVariables(value, null)

    /**
     * Recursively replaces every [Schema.VariableValue] in [value] with its binding.
     *
     * Throws after traversing the complete value when any variables are unbound. The exception
     * contains each unbound variable once. The result is null when [value] is a variable bound to
     * GraphQL null.
     *
     * @throws MissingVariablesException when one or more variables in [value] are unbound
     */
    fun instantiateAllVariables(value: Schema.Value): Schema.Value? {
        val unboundVariables = linkedMapOf<String, Schema.VariableValue>()
        val result = instantiateVariables(value, unboundVariables)
        if (unboundVariables.isNotEmpty()) {
            throw MissingVariablesException(unboundVariables.values.toList())
        }
        return result
    }

    override fun equals(other: Any?): Boolean = backingMap == other

    override fun hashCode(): Int = backingMap.hashCode()

    override fun toString(): String = backingMap.toString()

    companion object {
        /**
         * Constructs bindings after validating every supplied value.
         *
         * @throws IllegalArgumentException when a non-null binding is not a [Schema.InputValue]
         * @throws MissingVariablesException when any binding recursively contains variable values
         */
        internal fun from(
            schema: Schema,
            bindings: Map<String, Schema.Value?>,
        ): VariableBindings {
            val validatedBindings =
                buildMap<String, Schema.InputValue?> {
                    bindings.forEach { (variableName, value) ->
                        require(value == null || value is Schema.InputValue) {
                            "Variable $variableName contains a non-input GraphQL value"
                        }
                        put(variableName, value)
                    }
                }

            val unboundVariables = linkedMapOf<String, Schema.VariableValue>()
            val noBindings = VariableBindings(schema, emptyMap())
            validatedBindings.values.forEach { value ->
                noBindings.instantiateVariables(value, unboundVariables)
            }
            if (unboundVariables.isNotEmpty()) {
                throw MissingVariablesException(unboundVariables.values.toList())
            }

            return VariableBindings(schema, validatedBindings)
        }
    }

    private fun instantiateVariables(
        value: Schema.Value?,
        unboundVariables: MutableMap<String, Schema.VariableValue>? = null,
    ): Schema.Value? {
        if (value == null || value === Schema.ErrorValue) return value

        return when (value) {
            is Schema.VariableValue ->
                if (containsKey(value.variableName)) {
                    getValue(value.variableName)
                } else {
                    unboundVariables?.putIfAbsent(value.variableName, value)
                    value
                }

            is Schema.InputListValue ->
                schema.inputListValue(
                    value.inputListValues.map { element ->
                        instantiateVariables(element, unboundVariables) as Schema.InputValue?
                    },
                )

            is Schema.InputObjectValue ->
                schema.inputObjectValue(
                    type = value.type,
                    fields =
                        value.fieldValues.mapValues { (_, fieldValue) ->
                            instantiateVariables(
                                fieldValue,
                                unboundVariables,
                            ) as Schema.InputValue?
                        },
                )

            else -> value
        }
    }
}

/**
 * One or more distinct variable values that are unbound under the current assumptions.
 *
 * [variableValues] is non-empty and contains at most one value for each variable name.
 */
class MissingVariablesException(
    variableValues: List<Schema.VariableValue>,
) : NoSuchElementException(
        "Unbound variables: " +
            variableValues.joinToString { "$${it.variableName}" },
    ) {
    val variableValues: List<Schema.VariableValue> = variableValues.toList()

    init {
        require(this.variableValues.isNotEmpty()) {
            "MissingVariablesException requires at least one variable"
        }
        require(
            this.variableValues
                .map { it.variableName }
                .distinct()
                .size == this.variableValues.size,
        ) {
            "MissingVariablesException requires distinct variable names"
        }
    }
}
