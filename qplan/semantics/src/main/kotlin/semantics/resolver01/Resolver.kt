package semantics.resolver01

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.idKeyOf
import model.union
import semantics.correctresolution.argumentsContainErrorValue
import semantics.correctresolution.concreteObjectKey

/**
 * Resolves [selections] against an already-produced object value.
 *
 * A selected field with a registered field resolver is resolved from its arguments and an empty
 * object of the receiver's type. This version is therefore defined only for activated field
 * resolvers whose object fragments are empty. Every other selected field must already be present
 * in the receiver.
 *
 * The initial receiver may be an empty Query object because every non-`__typename` Query field has
 * a registered field resolver. Nested node references are delegated to [resolveNode].
 *
 * ### Domain
 *
 * A selected field without a registered field resolver remains in the output selection set of the
 * resolver that produced the receiver. Resolver values are assumed to contain every demanded field
 * in that output selection set, so the receiver contains the corresponding concrete key. A nested
 * node value crosses an ownership boundary before this lookup: [resolveNode] applies the node
 * resolver, and this operation then walks that resolver's value instead.
 *
 * @throws IllegalArgumentException when an activated field resolver has a nonempty object fragment
 * @throws model.MissingFieldException when an unregistered selected field is absent
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    val cells =
        selections
            .filter { selection -> type in selection.possibleTypes }
            .groupBy { selection -> selection.concreteObjectKey(type) }
            .mapValues { (key, fieldSelections) ->
                if (key.arguments.argumentsContainErrorValue()) {
                    EngineResult.Cell.Error
                } else {
                    val subselections =
                        fieldSelections.flatMap { selection -> selection.subselections }
                    val fieldValue =
                        if (key.field in world.executorRegistry) {
                            resolveField(key)
                        } else {
                            // The producing resolver supplies its demanded output-selection fields.
                            fieldValues.getValue(key)
                        }
                    EngineResult.Cell.of(
                        value = fieldValue.resolve(subselections),
                    )
                }
            }

    return EngineResult.Object.of(type, cells)
}

context(world: Assumptions)
private fun Value.Object.resolveField(key: Value.Key): Value.Output? {
    val resolver = world.executorRegistry.resolver(key.field)
    require(resolver.objectFragment.subselections.isEmpty()) {
        "Resolver for ${type.typeName}/${key.field.fieldName} has a nonempty object fragment"
    }
    return resolver.function(
        Value.Object.of(type),
        key.arguments,
    )
}

context(world: Assumptions)
private fun Value.Output?.resolve(selections: SelectionForest): EngineResult? =
    when (this) {
        null -> null
        Value.Error -> Value.Error
        is Value.Simple -> this

        is Value.Object ->
            if (type in world.executorRegistry) {
                resolveNode(selections)
            } else {
                resolve(selections)
            }

        is Value.OutputList ->
            EngineResult.List.of(
                typeExpr = typeExpr,
                cells =
                    values.map { value ->
                        EngineResult.Cell.of(
                            value = value.resolve(selections),
                        )
                    },
            )
    }

/**
 * Resolves this ID-bearing node reference, then delegates its resolver value to [resolve].
 *
 * The reference supplies the node's `id`. The registered node resolver repeats that `id` in its
 * returned object and supplies the remaining selected fields. The bridge retains `id` in the OER
 * even when it was not selected and checks agreement through union when it was selected.
 */
context(world: Assumptions)
fun Value.Object.resolveNode(selections: SelectionForest): EngineResult.Object {
    val idKey = requireNotNull(world.idKeyOf(type))
    val id = fieldValues.getValue(idKey)
    require(id != Value.Error && id is Value.ID) {
        "Node reference ${type.typeName}/${idKey.field.fieldName} must contain a non-error ID"
    }

    val nodeRef = EngineResult.Object.nodeRef(idKey.field, id)
    val resolverResult =
        world.executorRegistry
            .resolver(type)
            .function(id)
            .resolve(selections)

    return nodeRef.union(resolverResult)
}
