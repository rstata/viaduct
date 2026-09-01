package model.registry

import model.ObjectEngineResult
import model.Assumptions
import model.PathComponent
import model.requireField

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
 * @throws MissingResolverException when this field has no registered field resolver
 */
context(world: Assumptions)
fun ObjectEngineResult.GroundKey.demandsFromSibling(
    siblingKey: ObjectEngineResult.GroundKey,
    root: ObjectEngineResult,
    path: List<PathComponent> = emptyList(),
): Boolean {
    val field = this.field
    val objectType = field.containingDef
    val sibling = siblingKey.field
    require(sibling.containingDef == objectType) {
        "Sibling demand is defined only for fields on the same concrete object type"
    }
    require(world.schema.requireField(objectType.name, sibling.name) == sibling) {
        "${objectType.name}/${sibling.name} is not canonical in this world"
    }
    return siblingKey in
        world.resolverRegistry
            .resolver(field)
            .objectFragmentAt(root, path)
            .groundKeys()
}
