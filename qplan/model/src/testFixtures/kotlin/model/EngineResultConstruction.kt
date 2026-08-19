package model

import model.invariants.conformsToResultSchemaType

/** Constructs an object engine result by resolving type and field names in this reasoning world. */
fun Assumptions.engineResultOf(
    typeName: String,
    block: EngineResultScope.() -> Unit = {},
): ObjectEngineResult = schema.engineResultOf(typeName, block)

/** Constructs an object engine result by resolving type and field names in this schema. */
fun Schema.engineResultOf(
    typeName: String,
    block: EngineResultScope.() -> Unit = {},
): ObjectEngineResult {
    val type = requireType(typeName)
    require(type is Schema.Object) {
        "$typeName is not an object type"
    }
    return EngineResultScope(this, type)
        .apply(block)
        .build()
}

/** Constructs a list engine result in this reasoning world. */
fun Assumptions.listResultOf(
    typeExpr: TypeExpr<Schema.OutputTypeDef>,
    vararg values: Any?,
): ListEngineResult = schema.listResultOf(typeExpr, *values)

/** Constructs a list engine result whose elements have [typeExpr]. */
fun Schema.listResultOf(
    typeExpr: TypeExpr<Schema.OutputTypeDef>,
    vararg values: Any?,
): ListEngineResult =
    ListEngineResult.of(
        typeExpr = typeExpr,
        values = values.map { value -> coerceEngineResult(typeExpr, value) },
    )

@DslMarker
annotation class EngineResultDsl

/** Field-construction scope for [engineResultOf]. */
@EngineResultDsl
class EngineResultScope internal constructor(
    private val schema: Schema,
    private val type: Schema.Object,
) {
    private val values = linkedMapOf<ObjectEngineResult.GroundKey, EngineResult?>()
    private val accessResults = linkedMapOf<ObjectEngineResult.GroundKey, EngineResult>()

    /** Selects a field coordinate on this scope's object type. */
    fun field(
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): EngineResultFieldReference {
        require(arguments.map(Pair<String, Any?>::first).distinct().size == arguments.size) {
            "Arguments for ${type.name}/$fieldName must have distinct names"
        }
        return EngineResultFieldReference(
            key =
                ObjectEngineResult.GroundKey.of(
                    field = schema.requireObjectField(type.name, fieldName),
                    arguments = arguments.toMap(),
                ),
        )
    }

    /** Resolves this argumentless field to [value] with accepted access. */
    infix fun String.resolvesTo(value: Any?) {
        field(this).resolvesTo(value)
    }

    /** Resolves this argumentless field to [value], with [accessResult] determining access. */
    fun String.resolvesTo(
        value: Any?,
        accessResult: EngineResult,
    ) {
        field(this).resolvesTo(value, accessResult)
    }

    /** Resolves this exact field coordinate to [value] with accepted access. */
    infix fun EngineResultFieldReference.resolvesTo(value: Any?) {
        resolvesTo(value, true)
    }

    /** Resolves this exact field coordinate to [value], with [accessResult] determining access. */
    fun EngineResultFieldReference.resolvesTo(
        value: Any?,
        accessResult: EngineResult,
    ) {
        require(key !in values) {
            "Duplicate engine-result field ${type.name}/${key.field.name}"
        }
        values[key] = coerceEngineResult(key.field.type, value)
        accessResults[key] = accessResult
    }

    /** Constructs a nested object engine result using the same schema. */
    fun engineResultOf(
        typeName: String,
        block: EngineResultScope.() -> Unit = {},
    ): ObjectEngineResult = schema.engineResultOf(typeName, block)

    internal fun build(): ObjectEngineResult =
        ObjectEngineResult.of(
            type = type,
            values = values.toMap(),
            accessResults = accessResults.toMap(),
        )
}

/** One exact object-field coordinate selected in an [EngineResultScope]. */
class EngineResultFieldReference internal constructor(
    internal val key: ObjectEngineResult.GroundKey,
)

private fun coerceEngineResult(
    typeExpr: TypeExpr<Schema.OutputTypeDef>,
    value: Any?,
): EngineResult? {
    if (value.conformsToResultSchemaType(typeExpr)) return value

    return when (typeExpr) {
        is TypeExpr.List -> {
            require(value is List<*>) {
                "Expected a list result for $typeExpr"
            }
            ListEngineResult.of(
                typeExpr = typeExpr.elementType,
                values =
                    value.map { element ->
                        coerceEngineResult(typeExpr.elementType, element)
                    },
            )
        }

        is TypeExpr.Named ->
            when (val type = typeExpr.baseType) {
                is Schema.SimpleTypeDef ->
                    coerceSimpleValue(type, requireNotNull(value)).toEngineResult(type)
                is Schema.CompositeTypeDef ->
                    throw IllegalArgumentException(
                        "Expected an object engine result for ${type.name}",
                    )
            }
    }
}
