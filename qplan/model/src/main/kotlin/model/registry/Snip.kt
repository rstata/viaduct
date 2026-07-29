package model.registry

import model.Assumptions
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.toSelectionForest

/**
 * Supplies the selection input of this behavioral field's interpretation by projecting [result] to
 * its output selection set.
 *
 * For fixed non-selection inputs, the field's behavior produces [result] before [selections] are
 * considered. Projecting that same output for different requested selections makes the results
 * agree at every coordinate selected by both.
 *
 * Simple, null, and error results are unchanged. List results are projected element-wise. Object
 * projection retains selected non-behavioral fields and stops before every field for which
 * [Assumptions.behavioral] is true. This leaves a nested node's `id` field in the containing
 * resolver's output while omitting fields supplied by that node's resolver or by explicit field
 * resolvers. A type-conditioned selection that does not apply to a concrete object is omitted
 * before its nominal type is checked.
 *
 * This operation's reasoning scope assumes that every argument-bearing output field has an explicit
 * field resolver. Such a field is therefore a behavioral boundary, and every retained field is
 * argumentless.
 *
 * This must be a canonical behavioral field. Every applicable selection must be declared on the
 * concrete object type or one of its nominal supertypes.
 *
 * @throws IllegalArgumentException when a precondition is not met
 */
context(world: Assumptions)
fun Schema.OutputField.snip(
    result: Value.Output?,
    selections: SelectionForest,
): Value.Output? {
    require(world.behavioral(this)) {
        "Field ${containingType.typeName}/$fieldName is not behavioral"
    }
    return result.snipOutput(selections)
}

/**
 * Supplies the selection input of this node resolver by projecting [result] to the node resolver's
 * output selection set.
 *
 * At the root, projection retains fields supplied by this node resolver but omits the node's `id`
 * bridge field and fields with explicit field resolvers. Below the root it stops at every
 * [Assumptions.behavioral] field, just like field-resolver projection. Thus a node resolver may
 * supply its own behavioral fields without crossing into another resolver's behavior.
 *
 * This must be [result]'s canonical type and must have a registered node resolver. Every applicable
 * selection must be declared on the concrete object type or one of its nominal supertypes.
 *
 * @throws IllegalArgumentException when a precondition is not met
 */
context(world: Assumptions)
fun Schema.ObjectType.snip(
    result: Value.Object,
    selections: SelectionForest,
): Value.Object {
    require(result.type == this) {
        "Node resolver $typeName cannot project an object of type ${result.type.typeName}"
    }
    require(this in world.executorRegistry) {
        "No node resolver is registered for $typeName"
    }
    val applicableSelections = selections.filter { result.type in it.possibleTypes }

    val selectedFields =
        applicableSelections
            .groupBy { it.concreteObjectKey(result.type) }
            .mapNotNull { (key, fieldSelections) ->
                val concreteField = key.field
                val crossesBoundary =
                    concreteField.fieldName == "id" ||
                        concreteField.fieldName == "__typename" ||
                        concreteField in world.executorRegistry
                if (crossesBoundary) return@mapNotNull null

                val value = result.fieldValues.getValue(key)
                val selectedValue =
                    if (concreteField.typeExpr.baseType is Schema.SimpleType) {
                        value
                    } else {
                        val subselections = fieldSelections.flatMap { it.subselections }
                        value.snipOutput(subselections)
                    }
                key to selectedValue
            }
            .toMap()

    return Value.Object.of(result.type, selectedFields)
}

context(world: Assumptions)
private fun Value.Object.snip(
    selections: SelectionForest,
): Value.Object {
    val applicableSelections = selections.filter { type in it.possibleTypes }

    val selectedFields =
        applicableSelections
            .groupBy { it.concreteObjectKey(type) }
            .mapNotNull { (key, fieldSelections) ->
                val concreteField = key.field
                if (world.behavioral(concreteField)) return@mapNotNull null

                val value = fieldValues.getValue(key)
                val selectedValue =
                    if (concreteField.typeExpr.baseType is Schema.SimpleType) {
                        value
                    } else {
                        val subselections = fieldSelections.flatMap { it.subselections }
                        value.snipOutput(subselections)
                    }
                key to selectedValue
            }
            .toMap()

    return Value.Object.of(type, selectedFields)
}

context(world: Assumptions)
private fun Value.Output?.snipOutput(
    selections: SelectionForest,
): Value.Output? =
    when (this) {
        null,
        Value.Error,
        -> this

        is Value.Object -> snip(selections)
        is Value.OutputList ->
            Value.OutputList.of(
                typeExpr = typeExpr,
                values = values.map { it.snipOutput(selections) },
            )

        is Value.Simple -> {
            require(selections.isEmpty()) {
                "Cannot apply subselections to a simple value $this"
            }
            this
        }
    }

context(world: Assumptions)
private fun Selection.concreteObjectKey(type: Schema.ObjectType): Value.Key =
    Value.Key.of(
        field = world.schema.field(type.typeName, key.field.fieldName),
        arguments = key.arguments.fieldValues,
    )
