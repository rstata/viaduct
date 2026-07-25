package model

/**
 * Variable bindings that distinguish an unbound variable from one bound to GraphQL null.
 *
 * Unlike an ordinary [Map], [get] and [getValue] throw [MissingVariablesException] when used on an
 * unbound variable. Use [containsKey] to determine whether a variable is bound.
 *
 * Construct bindings with [from]. It takes a snapshot of the top-level map and recursively
 * requires every non-null value to be a [GraphQLInputValue] containing no
 * [GraphQLVariableValue]. [GraphQLErrorValue] is permitted.
 *
 * [Map] extension functions such as [Map.getOrElse] may call [get] and therefore throw instead of
 * applying their fallback. They should not be used when the variable may be unbound.
 */
class VariableBindings private constructor(
    private val backingMap: Map<String, GraphQLInputValue?>,
) : Map<String, GraphQLInputValue?> by backingMap {
    /** @throws MissingVariablesException when [key] is unbound */
    override operator fun get(key: String): GraphQLInputValue? = getValue(key)

    /** @throws MissingVariablesException when [key] is unbound */
    fun getValue(key: String): GraphQLInputValue? {
        if (!backingMap.containsKey(key)) {
            throw MissingVariablesException(
                listOf(GraphQLVariableValue.of(key)),
            )
        }
        return backingMap[key]
    }

    /**
     * Recursively replaces every bound [GraphQLVariableValue] in [value] with its binding.
     *
     * Unbound variables remain in the returned value. The result is null when [value] is a
     * variable bound to GraphQL null.
     */
    fun instantiateVariables(value: GraphQLValue): GraphQLValue? =
        instantiateVariables(value, null)

    /**
     * Recursively replaces every [GraphQLVariableValue] in [value] with its binding.
     *
     * Throws after traversing the complete value when any variables are unbound. The exception
     * contains each unbound variable once. The result is null when [value] is a variable bound to
     * GraphQL null.
     *
     * @throws MissingVariablesException when one or more variables in [value] are unbound
     */
    fun instantiateAllVariables(value: GraphQLValue): GraphQLValue? {
        val unboundVariables = linkedMapOf<String, GraphQLVariableValue>()
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
         * @throws IllegalArgumentException when a non-null binding is not a [GraphQLInputValue]
         * @throws MissingVariablesException when any binding recursively contains variable values
         */
        @JvmStatic
        fun from(bindings: Map<String, GraphQLValue?>): VariableBindings {
            val validatedBindings =
                buildMap<String, GraphQLInputValue?> {
                    bindings.forEach { (variableName, value) ->
                        require(value == null || value is GraphQLInputValue) {
                            "Variable $variableName contains a non-input GraphQL value"
                        }
                        put(variableName, value)
                    }
                }

            val unboundVariables = linkedMapOf<String, GraphQLVariableValue>()
            val noBindings = VariableBindings(emptyMap())
            validatedBindings.values.forEach { value ->
                noBindings.instantiateVariables(value, unboundVariables)
            }
            if (unboundVariables.isNotEmpty()) {
                throw MissingVariablesException(unboundVariables.values.toList())
            }

            return VariableBindings(validatedBindings)
        }
    }

    private fun instantiateVariables(
        value: GraphQLValue?,
        unboundVariables: MutableMap<String, GraphQLVariableValue>? = null,
    ): GraphQLValue? {
        if (value == null || value === GraphQLErrorValue) return value

        return when (value) {
            is GraphQLVariableValue ->
                if (containsKey(value.variableName)) {
                    getValue(value.variableName)
                } else {
                    unboundVariables?.putIfAbsent(value.variableName, value)
                    value
                }

            is GraphQLInputListValue ->
                GraphQLInputListValue.of(
                    value.inputListValues.map { element ->
                        instantiateVariables(element, unboundVariables) as GraphQLInputValue?
                    },
                )

            is GraphQLInputObjectValue ->
                GraphQLInputObjectValue.of(
                    typeName = value.inputObjectTypeName,
                    fields =
                        value.inputObjectFields.mapValues { (_, fieldValue) ->
                            instantiateVariables(
                                fieldValue,
                                unboundVariables,
                            ) as GraphQLInputValue?
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
    variableValues: List<GraphQLVariableValue>,
) : NoSuchElementException(
        "Unbound variables: " +
            variableValues.joinToString { "$${it.variableName}" },
    ) {
    val variableValues: List<GraphQLVariableValue> = variableValues.toList()

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
