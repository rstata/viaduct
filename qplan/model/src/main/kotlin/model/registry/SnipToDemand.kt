package model.registry

import viaduct.graphql.schema.ViaductSchema

import model.Arguments

import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineOutputListData
import model.SelectionForest
import model.engineObjectDataOf
import model.instantiateBindings
import model.merge
import model.objectKey
import model.outputValue
import model.schemaType
import viaduct.engine.api.EngineObjectData

/**
 * Supplies the selection input of a field resolver by projecting this selection-independent result
 * to the requested [demand].
 *
 * For fixed non-selection inputs, the field's behavior produces this result before [demand] is
 * considered. Projecting that same output for different demands makes the results agree at every
 * coordinate selected by both.
 *
 * Simple, null, and error results are unchanged. List results are projected element-wise. Object
 * projection retains demanded passive fields and stops before every field with a registered
 * resolver. A type-conditioned selection that does not apply to a concrete object is omitted before
 * its key is reconstructed against that object's concrete field. There is no implicit
 * node-reference retention or node-specific root projection; fixture-generated bridge fields appear
 * only when included in [demand].
 *
 * This operation's reasoning scope assumes that every argument-bearing output field has an explicit
 * field resolver. Such a field is therefore a resolver boundary, and every retained field is
 * argumentless.
 *
 * Every applicable selection in [demand] must be declared on the concrete object type or one of
 * its nominal supertypes. A selection retained below a resolver boundary must contain no
 * [Arguments.Variable] in its key arguments; a resolver selection may retain symbolic arguments
 * because projection stops before materializing its key.
 *
 * @throws IllegalArgumentException when a precondition is not met
 */
context(world: Assumptions)
internal fun EngineOutputData?.snipToDemand(demand: SelectionForest): EngineOutputData? =
    when (this) {
        null,
        is EngineErrorData,
        -> this

        is EngineObjectData.Sync -> snipObjectToDemand(demand)
        is List<*> -> {
            val values: EngineOutputListData =
                map { value -> value.snipToDemand(demand) }
            values
        }

        is Int,
        is Double,
        is String,
        is Boolean,
        -> {
            require(demand.isEmpty()) {
                "Cannot apply subselections to a simple value $this"
            }
            this
        }
        else -> throw ClassCastException("Unsupported engine output data: $this")
    }

context(world: Assumptions)
private fun EngineObjectData.Sync.snipObjectToDemand(
    demand: SelectionForest,
): EngineObjectData.Sync {
    val schemaType = this.schemaType
    val selectedFields =
        demand
            .merge(schemaType)
            .filter { selection ->
                selection.objectKey(schemaType).field !in world.resolverRegistry
            }
            .instantiateBindings()
            .byGroundKey()
            .map { (key, selection) ->
                val concreteField = key.field
                val arguments = key.arguments
                require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
                    "Passive object field ${schemaType.name}/${concreteField.name} " +
                        "must be argumentless"
                }
                val value = outputValue(concreteField.name)
                val selectedValue =
                    if (concreteField.type.baseTypeDef is ViaductSchema.SimpleTypeDef) {
                        value
                    } else {
                        value.snipToDemand(selection.subselections)
                    }
                concreteField.name to selectedValue
            }
            .toMap()
    return engineObjectDataOf(schemaType, selectedFields)
}
