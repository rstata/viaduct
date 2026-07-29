package model.registry

import model.Assumptions
import model.Schema
import model.Value

/**
 * Whether this registered field resolver directly demands [siblingKey] at the top level of its
 * object fragment.
 *
 * This relation is defined only when this field and [siblingKey] belong to the same concrete object
 * type and this field has a registered field resolver. A type-conditioned selection counts only
 * when it applies to that common concrete type. The selected key is specialized to the concrete
 * object type and its variables are instantiated before comparison. The relation does not search
 * beneath a top-level sibling selection.
 *
 * @throws IllegalArgumentException when the fields do not belong to the same concrete object type
 * or [siblingKey] is not canonical in this world
 * @throws MissingExecutorException when this field has no registered field resolver
 */
context(world: Assumptions)
fun Schema.OutputField.demandsFromSibling(
    siblingKey: Value.Key,
): Boolean {
    val objectType = containingType
    val sibling = siblingKey.field
    require(objectType is Schema.ObjectType && sibling.containingType == objectType) {
        "Sibling demand is defined only for fields on the same concrete object type"
    }
    require(world.schema.field(objectType.typeName, sibling.fieldName) == sibling) {
        "${objectType.typeName}/${sibling.fieldName} is not canonical in this world"
    }
    val selections = world.executorRegistry.resolver(this).objectFragment.subselections
    return !selections.all { selection ->
        objectType !in selection.possibleTypes ||
            selection.concreteObjectKey(objectType) != siblingKey
    }
}

context(world: Assumptions)
private fun model.Selection.concreteObjectKey(type: Schema.ObjectType): Value.Key =
    Value.Key.of(
        field = world.schema.field(type.typeName, key.field.fieldName),
        arguments =
            key.arguments.fieldValues.mapValues { (_, value) ->
                value?.let(world.variableValues::instantiateAllVariables)
            },
    )
