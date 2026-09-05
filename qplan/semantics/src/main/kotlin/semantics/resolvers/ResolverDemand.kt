package semantics.resolvers

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult

import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import semantics.shared.applicableGroundSelections
import model.flatMapToSelectionForest
import model.schemaType
import model.selectionForestOf
import semantics.correctresolution.argumentsContainErrorValue
import viaduct.engine.api.EngineObjectData
import semantics.shared.OperationContext

/**
 * Returns the applicable demand closed under the direct object fragments of its resolver fields.
 *
 * Each closure step normalizes under existing bindings, binds variables defined by newly discovered
 * resolver occurrences, and instantiates their direct object fragments at their exact identities.
 */
context(operation: OperationContext)
fun ViaductSchema.Object.closeResolverDemand(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    selections: SelectionForest,
    includeParentInputDemand: Boolean = false,
): ObjectSelectionForest =
    closeResolverDemand(
        root = root,
        path = path,
        selections = selections,
        expanded = emptySet(),
        includeParentInputDemand = includeParentInputDemand,
    )

/** Closes demand only for standard resolvers whose fields are absent from this source object. */
context(operation: OperationContext)
fun EngineObjectData.Sync.closeResolverDemand(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    selections: SelectionForest,
    includeParentInputDemand: Boolean = false,
): ObjectSelectionForest =
    schemaType.closeResolverDemand(
        root = root,
        path = path,
        selections = selections,
        expanded = emptySet(),
        includeParentInputDemand = includeParentInputDemand,
        expandResolver = { key ->
            if (!isPresent(key.field.name)) {
                true
            } else {
                require(key.field.args.isEmpty()) {
                    "Resolver output must not supply argument-bearing field " +
                        "${schemaType.name}/${key.field.name}"
                }
                false
            }
        },
    )

context(operation: OperationContext)
private fun ViaductSchema.Object.closeResolverDemand(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    selections: SelectionForest,
    expanded: Set<ObjectEngineResult.GroundKey>,
    includeParentInputDemand: Boolean,
    expandResolver: (ObjectEngineResult.GroundKey) -> Boolean = { true },
): ObjectSelectionForest {
    val parentInputDemand =
        if (includeParentInputDemand) {
            context(operation.world) {
                selections.inputParentDemand()
            }
        } else {
            selectionForestOf()
        }
    val applicableSelections =
        (selections + parentInputDemand).applicableGroundSelections(this)
    val unexpandedResolverKeys =
        applicableSelections.groundKeys().filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in operation.resolverRegistry &&
                expandResolver(key)
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) return applicableSelections

    unexpandedResolverKeys.bindFromArguments(root, path)
    val resolverDemand =
        unexpandedResolverKeys.flatMapToSelectionForest { key ->
            operation.resolverRegistry
                .resolver(key.field)
                .instantiateFragmentsAt(root, path + key)
                .objectFragment
                .constructionSelections
        }
    return closeResolverDemand(
        root = root,
        path = path,
        selections = applicableSelections + resolverDemand,
        expanded = expanded + unexpandedResolverKeys,
        includeParentInputDemand = includeParentInputDemand,
        expandResolver = expandResolver,
    )
}
