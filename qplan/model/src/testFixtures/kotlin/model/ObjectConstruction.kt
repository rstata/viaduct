package model

import viaduct.graphql.schema.ViaductSchema

import viaduct.engine.api.EngineObjectData

/** Constructs an object value by resolving type and field names in this reasoning world. */
fun Assumptions.objectOf(
    typeName: String,
    block: ObjectValueScope.() -> Unit = {},
): EngineObjectData.Sync = schema.objectOf(typeName, block)

/** Constructs an object value by resolving type and field names in this schema. */
fun ViaductSchema.objectOf(
    typeName: String,
    block: ObjectValueScope.() -> Unit = {},
): EngineObjectData.Sync {
    val type = requireType(typeName)
    require(type is ViaductSchema.Object) {
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
    private val schema: ViaductSchema,
    private val type: ViaductSchema.Object,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val fields = linkedMapOf<String, EngineObjectDataEntry>()
    private var isBuilt = false

    /** Selects a field coordinate on this scope's object type. */
    fun field(
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): ObjectFieldReference {
        require(arguments.map(Pair<String, Any?>::first).distinct().size == arguments.size) {
            "Arguments for ${type.name}/$fieldName must have distinct names"
        }
        val field = sourceSchema.field(type.name, fieldName)
        require(field is ViaductSchema.ObjectField) {
            "${type.name}/$fieldName does not lower to an object field"
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
            "Cannot assign fields after constructing ${type.name}"
        }
        require(scope === this@ObjectValueScope) {
            "A field reference cannot be assigned in another object scope"
        }
        val arguments = key.arguments
        require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
            "Passive object field ${type.name}/${key.field.name} must be argumentless"
        }
        val fieldName = key.field.name
        require(fieldName !in fields) {
            "Duplicate object field ${type.name}/${key.field.name}"
        }
        fields[fieldName] =
            EngineObjectDataEntry.of(
                selection = fieldName,
                field = key.field,
                value =
                    sourceSchema.lowerOutput(
                        key.field,
                        coerceOutputValue(sourceTypeExpr, value),
                    ),
            )
    }

    /** Constructs a nested object value using the same schema. */
    fun objectOf(
        typeName: String,
        block: ObjectValueScope.() -> Unit = {},
    ): EngineObjectData.Sync = schema.objectOf(typeName, block)

    internal fun build(): EngineObjectData.Sync {
        isBuilt = true
        return engineObjectDataOf(
            schemaType = type,
            fields = fields.values,
        )
    }
}

/** One exact object-field coordinate selected in an [ObjectValueScope]. */
class ObjectFieldReference internal constructor(
    internal val scope: ObjectValueScope,
    internal val key: ObjectEngineResult.GroundKey,
    internal val sourceTypeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
)

private fun coerceOutputValue(
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    value: Any?,
): EngineOutputData? {
    if (value == null || value == EngineErrorData) return value

    val elementType = typeExpr.unwrapList()
    if (elementType != null) {
        require(value is List<*>) {
            "Expected a list value for $typeExpr"
        }
        return value.map { coerceOutputValue(elementType, it) }
    }
    return when (val type = typeExpr.baseTypeDef) {
        is ViaductSchema.SimpleTypeDef -> coerceSimpleValue(type, value)
        is ViaductSchema.CompositeTypeDef -> {
            require(
                value is EngineObjectData.Sync &&
                    type.possibleObjectTypes.any { possibleType ->
                        possibleType.name == value.type.name
                    },
            ) {
                "Expected an object value for ${type.name}"
                    }
            value
        }
        else -> error("Output field has a non-output type")
    }
}
