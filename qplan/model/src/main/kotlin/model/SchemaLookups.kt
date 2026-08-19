package model

fun Schema.requireQueryTypeDef(): Schema.Object =
    queryTypeDef ?: throw Schema.MissingSchemaElementException("Query")

fun Schema.requireType(name: String): Schema.TypeDef =
    types[name] ?: throw Schema.MissingSchemaElementException(name)

fun Schema.requireField(
    typeName: String,
    fieldName: String,
): Schema.Field {
    val containingDef = requireType(typeName) as? Schema.CompositeTypeDef
    return containingDef?.field(fieldName)
        ?: throw Schema.MissingSchemaElementException(typeName, fieldName)
}

fun Schema.requireObjectField(
    typeName: String,
    fieldName: String,
): Schema.ObjectField {
    val containingDef = requireType(typeName) as? Schema.Object
    return containingDef?.field(fieldName)
        ?: throw Schema.MissingSchemaElementException(typeName, fieldName)
}

fun Schema.CompositeTypeDef.requireField(name: String): Schema.Field =
    field(name) ?: throw Schema.MissingSchemaElementException(this.name, name)

fun Schema.Object.requireField(name: String): Schema.ObjectField =
    field(name) ?: throw Schema.MissingSchemaElementException(this.name, name)

fun Schema.InputObjectLike.requireField(name: String): Schema.InputLikeField =
    field(name) ?: throw NoSuchElementException("Missing input field: $name")

fun Schema.Enum.requireValue(name: String): Schema.EnumValue =
    value(name) ?: throw NoSuchElementException("Missing enum value: ${this.name}/$name")
