package model.registry

import model.Assumptions
import model.Schema
import model.SelectionForest
import model.Value
import model.instantiateBindings
import model.merge
import model.objectKey

/**
 * Supplies the selection input of a field resolver by projecting this selection-independent result
 * to the requested [demand].
 *
 * For fixed non-selection inputs, the field's behavior produces this result before [demand] is
 * considered. Projecting that same output for different demands makes the results agree at every
 * coordinate selected by both.
 *
 * Simple, null, and error results are unchanged. List results are projected element-wise. Object
 * projection retains demanded non-behavioral fields and stops before every field for which
 * [Assumptions.behavioral] is true. A type-conditioned selection that does not apply to a concrete
 * object is omitted before its key is reconstructed against that object's concrete field. There is
 * no implicit node-reference retention or node-specific root projection; fixture-generated bridge
 * fields appear only when included in [demand].
 *
 * This operation's reasoning scope assumes that every argument-bearing output field has an explicit
 * field resolver. Such a field is therefore a behavioral boundary, and every retained field is
 * argumentless.
 *
 * Every applicable selection in [demand] must be declared on the concrete object type or one of
 * its nominal supertypes. A selection retained below a behavioral boundary must contain no
 * [Value.Variable] in its key arguments; a behavioral selection may retain symbolic arguments
 * because projection stops before materializing its key.
 *
 * @throws IllegalArgumentException when a precondition is not met
 */
context(world: Assumptions)
fun Value.Output?.snipToDemand(demand: SelectionForest): Value.Output? =
    when (this) {
        null,
        Value.Error,
        -> this

        is Value.Object -> snipObjectToDemand(demand)
        is Value.OutputList ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values = values.map { it.snipToDemand(demand) },
            )

        is Value.Simple -> {
            require(demand.isEmpty()) {
                "Cannot apply subselections to a simple value $this"
            }
            this
        }
    }

context(world: Assumptions)
private fun Value.Object.snipObjectToDemand(
    demand: SelectionForest,
): Value.Object {
    val selectedFields =
        demand
            .merge(type)
            .filter { selection -> !world.behavioral(selection.objectKey(type).field) }
            .instantiateBindings()
            .byGroundKey()
            .map { (key, selection) ->
                val concreteField = key.field
                val value = fieldValues.getValue(key)
                val selectedValue =
                    if (concreteField.typeExpr.baseType is Schema.SimpleType) {
                        value
                    } else {
                        value.snipToDemand(selection.subselections)
                    }
                key to selectedValue
            }
            .toMap()
    return Value.Object.of(type, selectedFields)
}
