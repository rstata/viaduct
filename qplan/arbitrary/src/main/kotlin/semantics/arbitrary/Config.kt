package semantics.arbitrary

/**
 * Immutable generator configuration modeled after Viaduct arbitrary's typed configuration map.
 */
class Config private constructor(
    private val values: Map<ConfigKey<*>, Any?>,
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: ConfigKey<T>): T =
        values[key] as? T ?: key.default

    operator fun <T> plus(value: Pair<ConfigKey<T>, T>): Config {
        value.first.validate(value.second)?.let { message ->
            throw IllegalArgumentException("$message: ${value.second}")
        }
        return Config(values + value)
    }

    operator fun plus(overrides: Config): Config =
        Config(values + overrides.values)

    companion object {
        val default: Config = Config(emptyMap())
    }
}

open class ConfigKey<T>(
    val default: T,
    val validate: (T) -> String?,
)

private fun positive(value: Int): String? =
    if (value > 0) null else "Value must be positive"

private fun nonNegative(value: Int): String? =
    if (value >= 0) null else "Value must be non-negative"

private fun range(value: IntRange): String? =
    if (!value.isEmpty() && value.first >= 0) null else "Range must be non-empty and non-negative"

private fun weight(value: Double): String? =
    if (value in 0.0..1.0) null else "Weight must be between 0.0 and 1.0"

object SchemaObjectCount : ConfigKey<IntRange>(1..4, ::range)
object ObjectFieldCount : ConfigKey<IntRange>(1..4, ::range)
object QueryFieldCount : ConfigKey<IntRange>(1..3, ::range)
object FieldArgumentWeight : ConfigKey<Double>(0.3, ::weight)
object ImplementationArgumentDefaultWeight : ConfigKey<Double>(0.3, ::weight)
object InputObjectCount : ConfigKey<IntRange>(0..2, ::range)
object InputObjectFieldCount : ConfigKey<IntRange>(1..3, ::range)
object InputObjectTypeWeight : ConfigKey<Double>(0.25, ::weight)
object InputListTypeWeight : ConfigKey<Double>(0.2, ::weight)
object MaxInputTypeDepth : ConfigKey<Int>(2, ::nonNegative)
object ExplicitFieldResolverWeight : ConfigKey<Double>(0.25, ::weight)
object ListTypeWeight : ConfigKey<Double>(0.2, ::weight)
object NullableTypeWeight : ConfigKey<Double>(0.45, ::weight)
object RecursiveOutputEdgeWeight : ConfigKey<Double>(0.2, ::weight)
object NullValueWeight : ConfigKey<Double>(0.15, ::weight)
object ErrorValueWeight : ConfigKey<Double>(0.05, ::weight)
object AliasWeight : ConfigKey<Double>(0.2, ::weight)
object DuplicateSelectionWeight : ConfigKey<Double>(0.15, ::weight)
object MinimumSelectionDepth : ConfigKey<Int>(0, ::nonNegative)
object MaxSelectionDepth : ConfigKey<Int>(4, ::positive)
object ListValueSize : ConfigKey<IntRange>(0..3, ::range)
object ArgumentsEnabled : ConfigKey<Boolean>(true, { null })
object InputObjectsEnabled : ConfigKey<Boolean>(true, { null })
object RecursiveInputTypesEnabled : ConfigKey<Boolean>(true, { null })
object RecursiveOutputEdgesEnabled : ConfigKey<Boolean>(true, { null })
object QueryFragmentsEnabled : ConfigKey<Boolean>(true, { null })
object InterfacesEnabled : ConfigKey<Boolean>(true, { null })
object UnionsEnabled : ConfigKey<Boolean>(true, { null })
object ListsEnabled : ConfigKey<Boolean>(true, { null })
object NodeResolversEnabled : ConfigKey<Boolean>(true, { null })
object ResolverFragmentsEnabled : ConfigKey<Boolean>(true, { null })
object ResolverFragmentWeight : ConfigKey<Double>(0.65, ::weight)
object ResolverFragmentDepth : ConfigKey<Int>(2, ::nonNegative)
object ResolverVariablesEnabled : ConfigKey<Boolean>(false, { null })
object ResolverVariableWeight : ConfigKey<Double>(0.5, ::weight)
object ResolverVariableCount : ConfigKey<IntRange>(1..3, ::range)

data class TestCaseCount(
    val schemas: Int = 10,
    val registriesPerSchema: Int = 3,
    val queriesPerSchema: Int = 5,
) {
    init {
        require(schemas > 0)
        require(registriesPerSchema > 0)
        require(queriesPerSchema > 0)
    }
}
