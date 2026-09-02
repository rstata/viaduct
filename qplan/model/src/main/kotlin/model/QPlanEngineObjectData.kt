package model

import viaduct.graphql.schema.ViaductSchema

import graphql.schema.GraphQLObjectType
import model.invariants.conformsToOutputSchemaType
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.errors.UnsetFieldException
import viaduct.graphql.schema.graphqljava.gjDef
import viaduct.utils.collections.HMap

/**
 * One construction-time EOD entry whose selection may be a field name or response alias.
 *
 * The schema field is retained through validation and then forgotten by the constructed EOD.
 */
sealed interface EngineObjectDataEntry {
    val selection: String
    val field: ViaductSchema.ObjectField
    val value: EngineOutputData?

    companion object {
        fun of(
            selection: String,
            field: ViaductSchema.ObjectField,
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
    schemaType: ViaductSchema.Object,
    fields: Map<String, EngineOutputData?> = emptyMap(),
): EngineObjectData.Sync =
    engineObjectDataOf(
        schemaType = schemaType,
        fields =
            fields.map { (name, value) ->
                val field = schemaType.field(name)
                require(field is ViaductSchema.ObjectField) {
                    "${schemaType.name} has no canonical object field named $name"
                }
                require(field.args.isEmpty()) {
                    "Passive object field ${schemaType.name}/$name must be argumentless"
                }
                EngineObjectDataEntry.of(name, field, value)
            },
    )

/**
 * Constructs a partial synchronous EOD from schema-associated selection values.
 *
 * [schemaType] supplies qplan's canonical lowered validation definition and the Engine API witness
 * associated with it. Source-backed objects expose the exact retained source GraphQL-Java object.
 */
fun engineObjectDataOf(
    schemaType: ViaductSchema.Object,
    fields: Iterable<EngineObjectDataEntry>,
): EngineObjectData.Sync =
    engineObjectDataOf(
        schemaType = schemaType,
        fields = fields,
        projectNodeBridges = false,
    )

/**
 * Constructs tenant-visible resolver input from values already validated in the lowered schema.
 *
 * Node bridge producers remain part of qplan's semantic field coordinates, but their bridge
 * objects are projected back to source-shaped values before crossing the Engine API boundary.
 */
fun materializedEngineObjectDataOf(
    schemaType: ViaductSchema.Object,
    fields: Iterable<EngineObjectDataEntry>,
): EngineObjectData.Sync =
    engineObjectDataOf(
        schemaType = schemaType,
        fields = fields,
        projectNodeBridges = true,
    )

private fun engineObjectDataOf(
    schemaType: ViaductSchema.Object,
    fields: Iterable<EngineObjectDataEntry>,
    projectNodeBridges: Boolean,
): EngineObjectData.Sync {
    val entries = fields.toList()
    entries.forEach { entry ->
        require(entry.field.containingDef == schemaType) {
            "${schemaType.name} cannot contain output field " +
                "${entry.field.containingDef.name}/${entry.field.name}"
        }
        require(entry.value.conformsToOutputSchemaType(entry.field.outputType)) {
            "${schemaType.name}/${entry.field.name} value does not conform to " +
                entry.field.type
        }
    }
    val values =
        entries.associate { entry ->
            entry.selection to
                if (projectNodeBridges) {
                    entry.sourceFacingValue()
                } else {
                    entry.value
                }
        }
    require(values.size == entries.size) {
        "Object ${schemaType.name} contains duplicate string selections"
    }
    return QPlanEngineObjectDataImpl(
        type = schemaType.engineObjectDataType,
        schemaType = schemaType,
        values = values,
    )
}

private fun EngineObjectDataEntry.sourceFacingValue(): EngineOutputData? =
    if (field.name.endsWith(NODE_BRIDGE_FIELD_SUFFIX)) {
        value.unwrapNodeBridge(field.outputType)
    } else {
        value
    }

private fun EngineOutputData?.unwrapNodeBridge(
    type: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
): EngineOutputData? {
    if (this == null || this is EngineErrorData) return this
    val elementType = type.unwrapList()
    if (elementType != null) {
        require(this is List<*>) {
            "Node bridge value for $type is not a list"
        }
        return map { element -> element.unwrapNodeBridge(elementType) }
    }
    require(this is EngineObjectData.Sync) {
        "Node bridge value for $type is not an object"
    }
    return outputValue(NODE_BRIDGE_PAYLOAD_FIELD)
}

internal val qplanEngineObjectDataTypeKey =
    HMap.Key.of<GraphQLObjectType>("QPlanEngineObjectDataType")

private val ViaductSchema.Object.engineObjectDataType: GraphQLObjectType
    get() =
        if (qplanEngineObjectDataTypeKey in holder) {
            holder[qplanEngineObjectDataTypeKey]
        } else {
            gjDef
        }

private const val NODE_BRIDGE_FIELD_SUFFIX = "_V_A_node"
private const val NODE_BRIDGE_PAYLOAD_FIELD = "node"

/**
 * The canonical qplan schema type retained by this qplan-owned EOD.
 *
 * Qplan's model admits only objects constructed by [engineObjectDataOf]. The downcast keeps the
 * concrete implementation private while making its canonical model type available without
 * inspecting the opaque GraphQL-Java [EngineObjectData.type] witness.
 */
val EngineObjectData.Sync.schemaType: ViaductSchema.Object
    get() =
        requireNotNull(qplanSchemaTypeOrNull) {
            "Engine object data for ${type.name} is not owned by qplan"
        }

internal val EngineObjectData.Sync.qplanSchemaTypeOrNull: ViaductSchema.Object?
    get() = (this as? QPlanEngineObjectData)?.schemaType

/**
 * Returns a qplan-owned selection in the engine output domain without applying resolver-read
 * error behavior.
 *
 * This is a temporary workaround for [EngineObjectData.Sync.get] and [EngineObjectData.fetch]
 * exposing a present error as an exception. Those operations should instead return the stored
 * [EngineErrorData], leaving the Tenant API implementation responsible for converting an erroneous
 * field read into a tenant-visible exception.
 */
fun EngineObjectData.Sync.outputValue(selection: String): EngineOutputData? {
    require(this is QPlanEngineObjectData) {
        "Engine object data for ${type.name} is not owned by qplan"
    }
    return outputValue(selection)
}

private data class EngineObjectDataEntryImpl(
    override val selection: String,
    override val field: ViaductSchema.ObjectField,
    override val value: EngineOutputData?,
) : EngineObjectDataEntry

internal interface QPlanEngineObjectData : EngineObjectData.Sync {
    val schemaType: ViaductSchema.Object

    fun outputValue(selection: String): EngineOutputData?
}

internal class EngineErrorDataReadException(
    val errorData: EngineErrorData,
) : RuntimeException(errorData.cause)

@OptIn(InternalApi::class)
private class QPlanEngineObjectDataImpl(
    override val type: GraphQLObjectType,
    override val schemaType: ViaductSchema.Object,
    values: Map<String, EngineOutputData?>,
) : QPlanEngineObjectData {
    private val values = values.toMap()

    override suspend fun fetch(selection: String): EngineOutputData? = get(selection)

    override suspend fun fetchOrNull(selection: String): EngineOutputData? = getOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = getSelections()

    override fun get(selection: String): EngineOutputData? {
        val value = outputValue(selection)
        value.firstErrorDataOrNull()?.let { errorData ->
            throw EngineErrorDataReadException(errorData)
        }
        return value
    }

    override fun outputValue(selection: String): EngineOutputData? {
        if (!isPresent(selection)) {
            throw UnsetFieldException(
                selection,
                type,
                "The selection is absent from qplan's partial object data",
            )
        }
        return values[selection]
    }

    override fun getOrNull(selection: String): EngineOutputData? =
        if (isPresent(selection)) get(selection) else null

    override fun isPresent(selection: String): Boolean = selection in values

    override fun getSelections(): Iterable<String> = values.keys

    override fun toString(): String = "type=${type.name} values=$values"
}

private fun EngineOutputData?.firstErrorDataOrNull(): EngineErrorData? =
    when (this) {
        is EngineErrorData -> this
        is List<*> -> firstNotNullOfOrNull { value -> value.firstErrorDataOrNull() }
        else -> null
    }
