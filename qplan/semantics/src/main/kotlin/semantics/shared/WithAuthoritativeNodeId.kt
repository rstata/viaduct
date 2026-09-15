package semantics.shared

import model.NodeReferenceIdentity
import model.ResolverOutputData
import model.SelectionForest
import model.engineObjectDataOf
import model.merge
import model.outputValue
import model.schemaType
import viaduct.engine.api.EngineObjectData

/**
 * Preserves the originating node reference's ID in an object result when [demand] selects `id`.
 * All resolver families use this after following reference tails, whose final resolver may return
 * a different ID. The returned object's type must match the originating node identity.
 */
internal fun ResolverOutputData?.withAuthoritativeNodeId(
    identity: NodeReferenceIdentity?,
    demand: SelectionForest,
): ResolverOutputData? {
    if (identity == null || this !is EngineObjectData.Sync) return this
    require(schemaType == identity.type) {
        "Node reference for ${identity.type.name} resolved to ${schemaType.name}"
    }
    val idField = identity.type.field("id")
        ?: throw IllegalArgumentException("Node type ${identity.type.name} has no id field")
    val idDemanded =
        demand.merge(identity.type).byKey().keys.any { key -> key.field == idField }
    if (!idDemanded) return this
    return engineObjectDataOf(
        identity.type,
        getSelections().associateWith(::outputValue) + (idField.name to identity.id),
    )
}
