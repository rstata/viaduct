package model

/** Constructs an object engine result by resolving type and field names in this reasoning world. */
fun Assumptions.engineResultOf(
    typeName: String,
    block: EngineResultScope.() -> Unit = {},
): EngineResult.Object = schema.engineResultOf(typeName, block)

/** Constructs an object engine result by resolving type and field names in this schema. */
fun Schema.engineResultOf(
    typeName: String,
    block: EngineResultScope.() -> Unit = {},
): EngineResult.Object {
    val type = type(typeName)
    require(type is Schema.ObjectType) {
        "$typeName is not an object type"
    }
    return EngineResultScope(this, type)
        .apply(block)
        .build()
}

/** Constructs a list engine result in this reasoning world. */
fun Assumptions.listResultOf(
    typeExpr: TypeExpr<Schema.OutputType>,
    vararg values: Any?,
): EngineResult.List = schema.listResultOf(typeExpr, *values)

/** Constructs a list engine result whose elements have [typeExpr]. */
fun Schema.listResultOf(
    typeExpr: TypeExpr<Schema.OutputType>,
    vararg values: Any?,
): EngineResult.List =
    EngineResult.List.of(
        typeExpr = typeExpr,
        values = values.map { value -> coerceEngineResult(typeExpr, value) },
    )

@DslMarker
annotation class EngineResultDsl

/** Field-construction scope for [engineResultOf]. */
@EngineResultDsl
class EngineResultScope internal constructor(
    private val schema: Schema,
    private val type: Schema.ObjectType,
) {
    private val values = linkedMapOf<Value.GroundKey, EngineResult?>()
    private val accessAccepted = linkedMapOf<Value.GroundKey, Value.Boolean>()

    /** Selects a field coordinate on this scope's object type. */
    fun field(
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): EngineResultFieldReference {
        require(arguments.map(Pair<String, Any?>::first).distinct().size == arguments.size) {
            "Arguments for ${type.typeName}/$fieldName must have distinct names"
        }
        return EngineResultFieldReference(
            key =
                Value.GroundKey.of(
                    field = schema.objectField(type.typeName, fieldName),
                    arguments = arguments.toMap(),
                ),
        )
    }

    /** Resolves this argumentless field to [value] with accepted access. */
    infix fun String.resolvesTo(value: Any?) {
        field(this).resolvesTo(value)
    }

    /** Resolves this argumentless field to [value], with [accept] determining access acceptance. */
    fun String.resolvesTo(
        value: Any?,
        accept: Value.Boolean,
    ) {
        field(this).resolvesTo(value, accept)
    }

    /** Resolves this exact field coordinate to [value] with accepted access. */
    infix fun EngineResultFieldReference.resolvesTo(value: Any?) {
        resolvesTo(value, Value.Boolean.of(true))
    }

    /** Resolves this exact field coordinate to [value], with [accept] determining access acceptance. */
    fun EngineResultFieldReference.resolvesTo(
        value: Any?,
        accept: Value.Boolean,
    ) {
        require(key !in values) {
            "Duplicate engine-result field ${type.typeName}/${key.field.fieldName}"
        }
        values[key] = coerceEngineResult(key.field.typeExpr, value)
        accessAccepted[key] = accept
    }

    /** Constructs a nested object engine result using the same schema. */
    fun engineResultOf(
        typeName: String,
        block: EngineResultScope.() -> Unit = {},
    ): EngineResult.Object = schema.engineResultOf(typeName, block)

    internal fun build(): EngineResult.Object =
        EngineResult.Object.of(
            type = type,
            values = values.toMap(),
            accessAccepted = accessAccepted.toMap(),
        )
}

/** One exact object-field coordinate selected in an [EngineResultScope]. */
class EngineResultFieldReference internal constructor(
    internal val key: Value.GroundKey,
)

private fun coerceEngineResult(
    typeExpr: TypeExpr<Schema.OutputType>,
    value: Any?,
): EngineResult? {
    if (value == null || value is EngineResult) return value

    return when (typeExpr) {
        is TypeExpr.List -> {
            require(value is List<*>) {
                "Expected a list result for $typeExpr"
            }
            EngineResult.List.of(
                typeExpr = typeExpr.elementType,
                values =
                    value.map { element ->
                        coerceEngineResult(typeExpr.elementType, element)
                    },
            )
        }

        is TypeExpr.Named ->
            when (val type = typeExpr.baseType) {
                is Schema.SimpleType -> coerceSimpleValue(type, value)
                is Schema.CompositeType ->
                    throw IllegalArgumentException(
                        "Expected an object engine result for ${type.typeName}",
                    )
            }
    }
}
