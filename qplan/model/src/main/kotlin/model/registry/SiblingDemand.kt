package model.registry

import model.Assumptions
import model.Schema
import model.SelectionForest
import model.Value

/**
 * Whether this registered field resolver key directly demands [siblingKey] at the top level of its
 * object fragment.
 *
 * This relation is defined only when this field and [siblingKey] belong to the same concrete object
 * type and this field has a registered field resolver. A type-conditioned selection counts only
 * when it applies to that common concrete type. The selected key is specialized to the concrete
 * object type before comparison. The relation is defined only when the selected key contains no
 * variables and does not search beneath a top-level sibling selection.
 *
 * @throws IllegalArgumentException when the fields do not belong to the same concrete object type
 * or [siblingKey] is not canonical in this world
 * @throws MissingExecutorException when this field has no registered field resolver
 */
context(world: Assumptions)
fun Value.Key.demandsFromSibling(
    siblingKey: Value.Key,
): Boolean {
    val field = this.field
    return field.demandsFromSibling(
        siblingKey = siblingKey,
        selections =
            world.executorRegistry
                .resolver(field)
                .objectFragment(arguments)
                .subselections,
    )
}

context(world: Assumptions)
private fun Schema.OutputField.demandsFromSibling(
    siblingKey: Value.Key,
    selections: SelectionForest,
): Boolean {
    val field = this
    val objectType = field.containingType
    val sibling = siblingKey.field
    require(objectType is Schema.ObjectType && sibling.containingType == objectType) {
        "Sibling demand is defined only for fields on the same concrete object type"
    }
    require(world.schema.field(objectType.typeName, sibling.fieldName) == sibling) {
        "${objectType.typeName}/${sibling.fieldName} is not canonical in this world"
    }
    return !selections.all { selection ->
        objectType !in selection.possibleTypes ||
            selection.concreteObjectKey(objectType) != siblingKey
    }
}

context(world: Assumptions)
private fun model.Selection.concreteObjectKey(type: Schema.ObjectType): Value.Key =
    Value.Key.of(
        field = world.schema.field(type.typeName, key.field.fieldName),
        arguments = key.arguments.fieldValues,
    )
