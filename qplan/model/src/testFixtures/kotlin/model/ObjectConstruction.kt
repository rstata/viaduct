package model

/** Constructs an object value by resolving type and field names in this reasoning world. */
fun Assumptions.objectOf(
    typeName: String,
    block: ObjectValueScope.() -> Unit = {},
): Value.Object = schema.objectOf(typeName, block)

/** Constructs an object value by resolving type and field names in this schema. */
fun Schema.objectOf(
    typeName: String,
    block: ObjectValueScope.() -> Unit = {},
): Value.Object {
    val type = type(typeName)
    require(type is Schema.ObjectType) {
        "$typeName is not an object type"
    }
    return ObjectValueScope(this, type)
        .apply(block)
        .build()
}

@DslMarker
annotation class ObjectValueDsl

/** Field-construction scope for [objectOf]. */
@ObjectValueDsl
class ObjectValueScope internal constructor(
    private val schema: Schema,
    private val type: Schema.ObjectType,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val fields = linkedMapOf<ObjectEngineResult.GroundKey, Value.Output?>()
    private var isBuilt = false

    /** Selects a field coordinate on this scope's object type. */
    fun field(
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): ObjectFieldReference {
        require(arguments.map(Pair<String, Any?>::first).distinct().size == arguments.size) {
            "Arguments for ${type.typeName}/$fieldName must have distinct names"
        }
        val field = sourceSchema.field(type.typeName, fieldName)
        require(field is Schema.ObjectField) {
            "${type.typeName}/$fieldName does not lower to an object field"
        }
        return ObjectFieldReference(
            scope = this,
            key =
                ObjectEngineResult.GroundKey.of(
                    field = field,
                    arguments = arguments.toMap(),
                ),
            sourceTypeExpr = sourceSchema.typeExpr(field),
        )
    }

    /** Assigns [value] to this argumentless field. */
    infix fun String.setTo(value: Any?) {
        this@ObjectValueScope.field(this).setTo(value)
    }

    /** Assigns [value] to this exact field coordinate. */
    infix fun ObjectFieldReference.setTo(value: Any?) {
        require(!isBuilt) {
            "Cannot assign fields after constructing ${type.typeName}"
        }
        require(scope === this@ObjectValueScope) {
            "A field reference cannot be assigned in another object scope"
        }
        require(key !in fields) {
            "Duplicate object field ${type.typeName}/${key.field.fieldName}"
        }
        fields[key] =
            sourceSchema.lowerOutput(
                key.field,
                coerceOutputValue(sourceTypeExpr, value),
            )
    }

    /** Constructs a nested object value using the same schema. */
    fun objectOf(
        typeName: String,
        block: ObjectValueScope.() -> Unit = {},
    ): Value.Object = schema.objectOf(typeName, block)

    internal fun build(): Value.Object {
        isBuilt = true
        return Value.Object.of(
            type = type,
            fields = fields.toMap(),
        )
    }
}

/** One exact object-field coordinate selected in an [ObjectValueScope]. */
class ObjectFieldReference internal constructor(
    internal val scope: ObjectValueScope,
    internal val key: ObjectEngineResult.GroundKey,
    internal val sourceTypeExpr: TypeExpr<Schema.OutputType>,
)

private fun coerceOutputValue(
    typeExpr: TypeExpr<Schema.OutputType>,
    value: Any?,
): Value.Output? {
    if (value == null || value is Value.Output) return value

    return when (typeExpr) {
        is TypeExpr.List -> {
            require(value is List<*>) {
                "Expected a list value for $typeExpr"
            }
            Value.OutputList.of(
                typeExpr = typeExpr.elementType,
                values = value.map { coerceOutputValue(typeExpr.elementType, it) },
            )
        }

        is TypeExpr.Named ->
            when (val type = typeExpr.baseType) {
                is Schema.SimpleType -> coerceSimpleValue(type, value)
                is Schema.CompositeType ->
                    throw IllegalArgumentException(
                        "Expected an object value for ${type.typeName}",
                    )
            }
    }
}
