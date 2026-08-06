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
    private val fields = linkedMapOf<Value.ObjectKey, Value.Output?>()
    private var isBuilt = false

    /** Selects a field coordinate on this scope's object type. */
    fun field(
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): ObjectFieldReference {
        require(arguments.map(Pair<String, Any?>::first).distinct().size == arguments.size) {
            "Arguments for ${type.typeName}/$fieldName must have distinct names"
        }
        return ObjectFieldReference(
            scope = this,
            key =
                Value.ObjectKey.of(
                    field = schema.objectField(type.typeName, fieldName),
                    arguments = arguments.toMap(),
                ),
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
        fields[key] = coerceOutputValue(key.field.typeExpr, value)
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
    internal val key: Value.ObjectKey,
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
