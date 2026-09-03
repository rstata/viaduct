package semantics.resolvers.resolver01

import model.ObjectEngineResult
import model.PathComponent
import model.requireField
import semantics.shared.objectFragmentAt
import semantics.shared.OperationContext

/** Whether this resolver key directly demands [siblingKey] in its top-level object fragment. */
context(operation: OperationContext)
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
    require(operation.schema.requireField(objectType.name, sibling.name) == sibling) {
        "${objectType.name}/${sibling.name} is not canonical in this world"
    }
    return siblingKey in
        operation.resolverRegistry
            .resolver(field)
            .objectFragmentAt(root, path)
            .groundKeys()
}
