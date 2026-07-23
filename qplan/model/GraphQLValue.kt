package model

/**
 * A field map whose [get] and [getValue] operations throw [MissingFieldException] when a key is
 * absent.
 *
 * A present key may still map to null.
 */
interface FieldMap<K, out V> : Map<K, V> {
    @Throws(MissingFieldException::class)
    override operator fun get(key: K): V

    @Throws(MissingFieldException::class)
    fun getValue(key: K): V = get(key)
}

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
 * [GraphQLFloatValue], [GraphQLStringValue], [GraphQLBooleanValue], or [GraphQLIdValue].
 *
 * Custom scalar values are not represented in this model. Therefore, this model can reason only
 * about schemas that do not have custom scalars.
 */
sealed interface GraphQLScalarValue : GraphQLSimpleValue

sealed interface GraphQLIntValue : GraphQLScalarValue {
    override val typeName: String
        get() = "Int"

    val intValue: Int
}

sealed interface GraphQLFloatValue : GraphQLScalarValue {
    override val typeName: String
        get() = "Float"

    /** Invariant: this value is finite; NaN and positive or negative infinity are excluded. */
    val floatValue: Double
}

sealed interface GraphQLStringValue : GraphQLScalarValue {
    override val typeName: String
        get() = "String"

    val stringValue: String
}

sealed interface GraphQLBooleanValue : GraphQLScalarValue {
    override val typeName: String
        get() = "Boolean"

    val booleanValue: Boolean
}

sealed interface GraphQLIdValue : GraphQLScalarValue {
    override val typeName: String
        get() = "ID"

    val idValue: String
}

sealed interface GraphQLEnumValue : GraphQLSimpleValue {
    override val typeName: String
        get() = enumTypeName

    val enumTypeName: String
    val enumValue: String
}

/**
 * When an input list contains potentially nested unbound variable values, equality is
 * conservative; see [GraphQLVariableValue].
 */
sealed interface GraphQLInputList : GraphQLInputValue {
    val inputListValues: List<GraphQLInputValue?>
}

sealed interface GraphQLOutputList : GraphQLOutputValue {
    val outputListValues: List<GraphQLOutputValue?>
}

/**
 * An input object whose fields may contain nested unbound variable values.
 *
 * Equality is conservative when such variables are present; see [GraphQLVariableValue].
 */
sealed interface GraphQLInputObject : GraphQLInputValue {
    val inputObjectTypeName: String
    val inputObjectFields: FieldMap<String, GraphQLInputValue?>
}

sealed interface GraphQLOutputObject : GraphQLOutputValue {
    val outputObjectTypeName: String
    val outputObjectFields: FieldMap<String, GraphQLOutputValue?>
}

/**
 * A symbolic reference to an execution variable.
 *
 * We assume that variables involved in any execution all have unique, non-conflicting names, so
 * when two variable values with the same name appear, it is safe to assume that their actual
 * values will be the same. When comparing a variable value to another value, we attempt to look
 * up its value in [variableValues]. If an entry exists, the variable is "bound," and we compare
 * its bound value with the other value. [equals] returns false for an unbound variable unless it
 * is compared with an unbound variable of the same name.
 *
 * When [equals] involving variable values returns true, the two values are guaranteed to be equal
 * under all possible variable bindings. When [equals] involving only bound variable values
 * returns false, the two values are guaranteed not to be equal. However, when [equals] involving
 * an unbound variable returns false, the two values are not necessarily equal but may become equal
 * depending on the eventual bindings.
 */
sealed interface GraphQLVariableValue : GraphQLInputValue {
    val variableName: String
}

class UnboundVariableException(
    val variableName: String,
) : NoSuchElementException("Unbound variable: $variableName")

/**
 * Variable bindings that distinguish an unbound variable from one bound to GraphQL null.
 *
 * Looking up an absent variable throws [UnboundVariableException]. Use [containsKey] to determine
 * whether a variable is bound without throwing.
 */
interface VariableBindings : Map<String, GraphQLInputValue?> {
    @Throws(UnboundVariableException::class)
    override operator fun get(key: String): GraphQLInputValue?

    @Throws(UnboundVariableException::class)
    fun getValue(key: String): GraphQLInputValue? = get(key)
}

/**
 * The global map of known variable bindings; not every variable is necessarily bound.
 *
 * A bound value may be null, representing the GraphQL null value, but a non-null bound value
 * cannot contain a [GraphQLVariableValue] anywhere in its nested structure. Variable-to-variable
 * bindings, including nested references and cycles, are therefore excluded. A variable absent
 * from the map is unbound or unknown in this model; there is no separate undefined value.
 * A variable may be bound to [GraphQLErrorValue] because variables can be supplied by providers
 * or fields, either of which may fail.
 *
 * See [GraphQLVariableValue] for how bindings help determine equality.
 */
val variableValues: VariableBindings = establishAssumptions()

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
    GraphQLIdValue,
    GraphQLEnumValue,
    GraphQLInputList,
    GraphQLOutputList,
    GraphQLInputObject,
    GraphQLOutputObject,
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

    override val inputObjectFields: FieldMap<String, GraphQLInputValue?>
        get() = unsupported()

    override val outputObjectTypeName: String
        get() = unsupported()

    override val outputObjectFields: FieldMap<String, GraphQLOutputValue?>
        get() = unsupported()

    override val variableName: String
        get() = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("GraphQLErrorValue has no observable properties")
}

class MissingFieldException(
    val typeName: String,
    val fieldName: String,
) : NoSuchElementException("Missing field: $typeName.$fieldName")
