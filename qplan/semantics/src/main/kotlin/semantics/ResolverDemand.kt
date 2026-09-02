package semantics

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult

import model.Assumptions
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.applicableGroundSelections
import model.flatMapToSelectionForest
import model.schemaType
import semantics.correctresolution.argumentsContainErrorValue
import viaduct.engine.api.EngineObjectData

/**
 * Returns the applicable demand closed under the direct object fragments of its resolver fields.
 *
 * Each closure step normalizes under existing bindings, binds variables defined by newly discovered
 * resolver occurrences, and instantiates their direct object fragments at their exact identities.
 */
context(world: Assumptions)
fun ViaductSchema.Object.closeResolverDemand(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    selections: SelectionForest,
): ObjectSelectionForest =
    closeResolverDemand(
        root = root,
        path = path,
        selections = selections,
        expanded = emptySet(),
    )

/** Closes demand only for standard resolvers whose fields are absent from this source object. */
context(world: Assumptions)
fun EngineObjectData.Sync.closeResolverDemand(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    selections: SelectionForest,
): ObjectSelectionForest =
    schemaType.closeResolverDemand(
        root = root,
        path = path,
        selections = selections,
        expanded = emptySet(),
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

context(world: Assumptions)
private fun ViaductSchema.Object.closeResolverDemand(
    root: ObjectEngineResult,
    path: List<PathComponent>,
    selections: SelectionForest,
    expanded: Set<ObjectEngineResult.GroundKey>,
    expandResolver: (ObjectEngineResult.GroundKey) -> Boolean = { true },
): ObjectSelectionForest {
    val applicableSelections = selections.applicableGroundSelections(this)
    val unexpandedResolverKeys =
        applicableSelections.groundKeys().filter { key ->
            key !in expanded &&
                !key.arguments.argumentsContainErrorValue() &&
                key.field in world.resolverRegistry &&
                expandResolver(key)
        }.toSet()

    if (unexpandedResolverKeys.isEmpty()) return applicableSelections

    unexpandedResolverKeys.bindFromArguments(root, path)
    val resolverDemand =
        unexpandedResolverKeys.flatMapToSelectionForest { key ->
            world.resolverRegistry
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
        expandResolver = expandResolver,
    )
}
