package model

/**
 * Implementations of these interfaces are mathematical values: equality is value equality
 * over the properties exposed by the interface. When an input value contains unbound variable
 * values, equality is conservative; see [GraphQLVariableValue].
 */
sealed interface GraphQLValue

sealed interface GraphQLInputValue : GraphQLValue

sealed interface GraphQLOutputValue : GraphQLValue

sealed interface GraphQLSimpleValue : GraphQLInputValue, GraphQLOutputValue, EngineResult

/**
 * A value in the model's closed universe of built-in scalar leaf types: [GraphQLIntValue],
 * [GraphQLFloatValue], [GraphQLStringValue], [GraphQLBooleanValue], or [GraphQLIDValue].
 *
 * Custom scalar values are not represented in this model. Therefore, this model can reason only
 * about schemas that do not have custom scalars.
 */
sealed interface GraphQLScalarValue : GraphQLSimpleValue

sealed interface GraphQLIntValue : GraphQLScalarValue {
    override val typeName: String
        get() = "Int"

    val intValue: Int

    companion object {
        @JvmStatic
        fun of(value: Int): GraphQLIntValue = DefaultGraphQLIntValue(value)
    }
}

sealed interface GraphQLFloatValue : GraphQLScalarValue {
    override val typeName: String
        get() = "Float"

    /** Invariant: this value is finite; NaN and positive or negative infinity are excluded. */
    val floatValue: Double

    companion object {
        @JvmStatic
        fun of(value: Double): GraphQLFloatValue {
            require(value.isFinite()) { "GraphQL Float values must be finite" }
            return DefaultGraphQLFloatValue(value)
        }
    }
}

sealed interface GraphQLStringValue : GraphQLScalarValue {
    override val typeName: String
        get() = "String"

    val stringValue: String

    companion object {
        @JvmStatic
        fun of(value: String): GraphQLStringValue = DefaultGraphQLStringValue(value)
    }
}

sealed interface GraphQLBooleanValue : GraphQLScalarValue {
    override val typeName: String
        get() = "Boolean"

    val booleanValue: Boolean

    companion object {
        @JvmStatic
        fun of(value: Boolean): GraphQLBooleanValue = DefaultGraphQLBooleanValue(value)
    }
}

sealed interface GraphQLIDValue : GraphQLScalarValue {
    override val typeName: String
        get() = "ID"

    val idValue: String

    companion object {
        @JvmStatic
        fun of(value: String): GraphQLIDValue = DefaultGraphQLIDValue(value)
    }
}

sealed interface GraphQLEnumValue : GraphQLSimpleValue {
    override val typeName: String
        get() = enumTypeName

    val enumTypeName: String
    val enumValue: String

    companion object {
        @JvmStatic
        fun of(
            enumTypeName: String,
            enumValue: String,
        ): GraphQLEnumValue = DefaultGraphQLEnumValue(enumTypeName, enumValue)
    }
}

/**
 * When an input list contains potentially nested unbound variable values, equality is
 * conservative; see [GraphQLVariableValue].
 */
sealed interface GraphQLInputListValue : GraphQLInputValue {
    val inputListValues: List<GraphQLInputValue?>

    companion object {
        @JvmStatic
        fun of(values: List<GraphQLInputValue?>): GraphQLInputListValue =
            DefaultGraphQLInputListValue(values.toList())
    }
}

sealed interface GraphQLListValue : GraphQLOutputValue {
    val outputListValues: List<GraphQLOutputValue?>
}

/**
 * An input object whose fields may contain nested unbound variable values.
 *
 * Equality is conservative when such variables are present; see [GraphQLVariableValue].
 */
sealed interface GraphQLInputObjectValue : GraphQLInputValue {
    val inputObjectTypeName: String
    val inputObjectFields: FieldValues<GraphQLInputValue>

    companion object {
        @JvmStatic
        fun of(
            typeName: String,
            fields: Map<String, GraphQLInputValue?>,
        ): GraphQLInputObjectValue =
            DefaultGraphQLInputObjectValue(
                inputObjectTypeName = typeName,
                inputObjectFields = FieldValues(typeName, fields.toMap()),
            )
    }
}

sealed interface GraphQLObjectValue : GraphQLOutputValue {
    val outputObjectTypeName: String
    val outputObjectFields: FieldValues<GraphQLOutputValue>
}

/**
 * A map from field names to values.
 *
 * Unlike an ordinary [Map], [get] and [getValue] throw [MissingFieldException] when the requested
 * field does not exist or, for an output object, has not been set. A present field may still map
 * to null.
 *
 * [Map] extension functions such as [Map.getOrElse] may call [get] and therefore throw instead of
 * applying their fallback. Check [containsKey] before looking up a field whose presence is unknown.
 */
class FieldValues<out V : GraphQLValue>(
    val typeName: String,
    private val backingMap: Map<String, V?>,
) : Map<String, V?> by backingMap {
    /** @throws MissingFieldException when [key] is not present */
    override operator fun get(key: String): V? = getValue(key)

    /** @throws MissingFieldException when [key] is not present */
    fun getValue(key: String): V? {
        if (!backingMap.containsKey(key)) {
            throw MissingFieldException(typeName, key)
        }
        return backingMap[key]
    }

    override fun equals(other: Any?): Boolean = backingMap == other

    override fun hashCode(): Int = backingMap.hashCode()

    override fun toString(): String = backingMap.toString()
}

class MissingFieldException(
    val typeName: String,
    val fieldName: String,
) : NoSuchElementException("Missing field: $typeName.$fieldName")

/**
 * A symbolic reference to an execution variable.
 *
 * We assume that variables involved in any execution all have unique, non-conflicting names, so
 * when two variable values with the same name appear, it is safe to assume that their actual
 * values will be the same. When comparing a variable value to another value, we attempt to look
 * up its value in [GlobalAssumptions.variableValues]. If an entry exists, the variable is "bound,"
 * and we compare its bound value with the other value. [equals] returns false for an unbound
 * variable unless it is compared with an unbound variable of the same name.
 *
 * When [equals] involving variable values returns true, the two values are guaranteed to be equal
 * under all possible variable bindings. When [equals] involving only bound variable values
 * returns false, the two values are guaranteed not to be equal. However, when [equals] involving
 * an unbound variable returns false, the two values are not necessarily equal but may become equal
 * depending on the eventual bindings.
 */
sealed interface GraphQLVariableValue : GraphQLInputValue {
    val variableName: String

    companion object {
        @JvmStatic
        fun of(variableName: String): GraphQLVariableValue =
            DefaultGraphQLVariableValue(variableName)
    }
}

/**
 * The bottom value of the GraphQL value hierarchy.
 *
 * Kotlin has no user-definable bottom value, so this object explicitly implements every leaf
 * value interface. Its properties have no value and therefore cannot be observed.
 *
 * This model intentionally collapses all GraphQL error metadata, including messages, paths,
 * extensions, and error multiplicity, into this single value. That metadata is an unnecessary
 * complication for the reasoning this model is intended to support.
 */
object GraphQLErrorValue :
    GraphQLIntValue,
    GraphQLFloatValue,
    GraphQLStringValue,
    GraphQLBooleanValue,
    GraphQLIDValue,
    GraphQLEnumValue,
    GraphQLInputListValue,
    GraphQLListValue,
    GraphQLInputObjectValue,
    GraphQLObjectValue,
    GraphQLVariableValue {
    override val typeName: String
        get() = unsupported()

    override val intValue: Int
        get() = unsupported()

    override val floatValue: Double
        get() = unsupported()

    override val stringValue: String
        get() = unsupported()

    override val booleanValue: Boolean
        get() = unsupported()

    override val idValue: String
        get() = unsupported()

    override val enumTypeName: String
        get() = unsupported()

    override val enumValue: String
        get() = unsupported()

    override val inputListValues: List<GraphQLInputValue?>
        get() = unsupported()

    override val outputListValues: List<GraphQLOutputValue?>
        get() = unsupported()

    override val inputObjectTypeName: String
        get() = unsupported()

    override val inputObjectFields: FieldValues<GraphQLInputValue>
        get() = unsupported()

    override val outputObjectTypeName: String
        get() = unsupported()

    override val outputObjectFields: FieldValues<GraphQLOutputValue>
        get() = unsupported()

    override val variableName: String
        get() = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("GraphQLErrorValue has no observable properties")
}

private data class DefaultGraphQLIntValue(
    override val intValue: Int,
) : GraphQLIntValue

private data class DefaultGraphQLFloatValue(
    override val floatValue: Double,
) : GraphQLFloatValue

private data class DefaultGraphQLStringValue(
    override val stringValue: String,
) : GraphQLStringValue

private data class DefaultGraphQLBooleanValue(
    override val booleanValue: Boolean,
) : GraphQLBooleanValue

private data class DefaultGraphQLIDValue(
    override val idValue: String,
) : GraphQLIDValue

private data class DefaultGraphQLEnumValue(
    override val enumTypeName: String,
    override val enumValue: String,
) : GraphQLEnumValue

private data class DefaultGraphQLInputListValue(
    override val inputListValues: List<GraphQLInputValue?>,
) : GraphQLInputListValue

private data class DefaultGraphQLInputObjectValue(
    override val inputObjectTypeName: String,
    override val inputObjectFields: FieldValues<GraphQLInputValue>,
) : GraphQLInputObjectValue

private data class DefaultGraphQLVariableValue(
    override val variableName: String,
) : GraphQLVariableValue
