package model

import graphql.schema.GraphQLObjectType
import model.invariants.conformsToOutputSchemaType
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.errors.UnsetFieldException

/**
 * One construction-time EOD entry whose selection may be a field name or response alias.
 *
 * The schema field is retained through validation and then forgotten by the constructed EOD.
 */
sealed interface EngineObjectDataEntry {
    val selection: String
    val field: Schema.ObjectField
    val value: EngineOutputData?

    companion object {
        fun of(
            selection: String,
            field: Schema.ObjectField,
            value: EngineOutputData?,
        ): EngineObjectDataEntry = EngineObjectDataEntryImpl(selection, field, value)
    }
}

/**
 * Constructs a passive object whose selections are canonical argumentless field names.
 *
 * Every supplied value conforms to its field's output type.
 */
fun engineObjectDataOf(
    schemaType: Schema.ObjectType,
    fields: Map<String, EngineOutputData?> = emptyMap(),
): EngineObjectData.Sync =
    engineObjectDataOf(
        schemaType = schemaType,
        fields =
            fields.map { (name, value) ->
                val field = schemaType.fields[name]
                require(field is Schema.ObjectField) {
                    "${schemaType.typeName} has no canonical object field named $name"
                }
                require(field.arguments.fields.isEmpty()) {
                    "Passive object field ${schemaType.typeName}/$name must be argumentless"
                }
                EngineObjectDataEntry.of(name, field, value)
            },
    )

/**
 * Constructs a partial synchronous EOD from schema-associated selection values.
 *
 * [schemaType] supplies qplan's canonical validation definition and its canonical opaque
 * GraphQL-Java definition for the production EOD type.
 */
fun engineObjectDataOf(
    schemaType: Schema.ObjectType,
    fields: Iterable<EngineObjectDataEntry>,
): EngineObjectData.Sync {
    val entries = fields.toList()
    entries.forEach { entry ->
        require(entry.field.containingType == schemaType) {
            "${schemaType.typeName} cannot contain output field " +
                "${entry.field.containingType.typeName}/${entry.field.fieldName}"
        }
        require(entry.value.conformsToOutputSchemaType(entry.field.typeExpr)) {
            "${schemaType.typeName}/${entry.field.fieldName} value does not conform to " +
                entry.field.typeExpr
        }
    }
    val values = entries.associate { entry -> entry.selection to entry.value }
    require(values.size == entries.size) {
        "Object ${schemaType.typeName} contains duplicate string selections"
    }
    return QPlanEngineObjectDataImpl(
        type = schemaType.gjDef,
        schemaType = schemaType,
        values = values,
    )
}

/**
 * The canonical qplan schema type retained by this qplan-owned EOD.
 *
 * Qplan's model admits only objects constructed by [engineObjectDataOf]. The downcast keeps the
 * concrete implementation private while making its canonical model type available without
 * inspecting the opaque GraphQL-Java [EngineObjectData.type] witness.
 */
val EngineObjectData.Sync.schemaType: Schema.ObjectType
    get() = (this as QPlanEngineObjectDataImpl).schemaType

private data class EngineObjectDataEntryImpl(
    override val selection: String,
    override val field: Schema.ObjectField,
    override val value: EngineOutputData?,
) : EngineObjectDataEntry

@OptIn(InternalApi::class)
private class QPlanEngineObjectDataImpl(
    override val type: GraphQLObjectType,
    val schemaType: Schema.ObjectType,
    values: Map<String, EngineOutputData?>,
) : EngineObjectData.Sync {
    private val values = values.toMap()

    override suspend fun fetch(selection: String): Any? = get(selection)

    override suspend fun fetchOrNull(selection: String): Any? = getOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = getSelections()

    override fun get(selection: String): Any? {
        if (!isPresent(selection)) {
            throw UnsetFieldException(
                selection,
                type,
                "The selection is absent from qplan's partial object data",
            )
        }
        return values[selection]
    }

    override fun getOrNull(selection: String): Any? =
        if (isPresent(selection)) values[selection] else null

    override fun isPresent(selection: String): Boolean = selection in values

    override fun getSelections(): Iterable<String> = values.keys

    override fun equals(other: Any?): Boolean =
        other is QPlanEngineObjectDataImpl &&
            schemaType == other.schemaType &&
            values == other.values

    override fun hashCode(): Int = 31 * schemaType.hashCode() + values.hashCode()

    override fun toString(): String = "type=${type.name} values=$values"
}
