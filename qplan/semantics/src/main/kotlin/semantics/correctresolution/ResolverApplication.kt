package semantics.correctresolution

import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineOutputData
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Selection
import model.SelectionForest
import model.concatenateSelectionForests
import model.engineObjectDataOf
import model.merge
import model.requireQueryTypeDef
import semantics.shared.groundedArguments
import model.selectionForestOf
import semantics.shared.CycleCheckState
import semantics.shared.materialize
import semantics.shared.OperationContext
import semantics.shared.ResolverObservations
import viaduct.engine.api.EngineObjectData
import java.util.IdentityHashMap

internal class ReappliedResolver(
    val output: EngineOutputData?,
)

internal class ResolverApplicationCache(
    val root: ObjectEngineResult,
) {
    private val applications =
        IdentityHashMap<
            ObjectEngineResult,
            MutableMap<ObjectEngineResult.ObjectKey, CachedResolverApplication>,
        >()

    fun getOrPut(
        result: ObjectEngineResult,
        key: ObjectEngineResult.ObjectKey,
        compute: () -> ReappliedResolver?,
    ): ReappliedResolver? {
        val byKey = applications.getOrPut(result, ::linkedMapOf)
        return byKey.getOrPut(key) {
            CachedResolverApplication(compute())
        }.application
    }
}

private class CachedResolverApplication(
    val application: ReappliedResolver?,
)

/**
 * Reconstructs source ownership while traversing the completed result.
 *
 * The extensional correctness judgment re-evaluates deterministic resolver relations. An
 * argumentless field present in that output belongs to its ancestor source; an absent registered
 * field belongs to its standard resolver.
 */
context(
    operation: OperationContext,
    resolverApplicationCache: ResolverApplicationCache,
)
internal fun ObjectEngineResult.reapplyResolver(
    key: ObjectEngineResult.ObjectKey,
    path: List<PathComponent>,
): ReappliedResolver? =
    resolverApplicationCache.getOrPut(this, key) {
        val arguments = key.groundedArguments() as? Arguments.Resolved ?: return@getOrPut null
        val resolver = operation.resolverRegistry.resolver(key.field)
        val coordinate = path + key
        val fragments =
            resolver.fragmentsSatisfiedBy(
                root = resolverApplicationCache.root,
                result = this,
                path = coordinate,
            ) ?: return@getOrPut null
        val objectFragment = fragments.objectFragment
        val input: EngineObjectData.Sync =
            runBlocking {
                context(operation, CycleCheckState.createNOP()) {
                    materialize(
                        selections = objectFragment.materializeSelections,
                        reader = coordinate,
                    )
                }
            }
        val resolverArguments =
            Arguments.Resolved.of(
                field = key.field,
                fields = arguments.fieldValues,
            )
        val resolverOccurrenceId = objectFragment.resolverOccurrenceId
        val queryFragment = fragments.queryFragment
        val queryValue =
            if (queryFragment.constructionSelections.isEmpty()) {
                engineObjectDataOf(operation.schema.requireQueryTypeDef())
            } else {
                val queryResult =
                    (operation.resolverObserver as? ResolverObservations)
                        ?.queryFragmentResults(resolverOccurrenceId)
                        ?.singleOrNull()
                        ?: return@getOrPut null
                val querySelections =
                    queryFragment.constructionSelections
                        .merge(operation.schema.requireQueryTypeDef())
                if (!queryResult.correctResolution(querySelections)) {
                    return@getOrPut null
                }
                runBlocking {
                    context(operation, CycleCheckState.createNOP()) {
                        queryResult.materialize(
                            selections = queryFragment.materializeSelections,
                            reader = coordinate,
                        )
                    }
                }
            }
        ReappliedResolver(
            context(operation.world) {
                resolver.evaluateRelation(
                    input = input,
                    queryValue = queryValue,
                    arguments = resolverArguments,
                    selections = getCell(key).getValue().get().completedOutputDemand(),
                )
            },
        )
    }

/**
 * Reconstructs one canonical demand from the completed output occurrence under judgment.
 *
 * This is an extensional reapplication input, not a claim about the exact demand supplied by a
 * resolver algorithm. Selective resolver relations are required to agree on coordinates shared by
 * different demands, so this demand is sufficient for completed-result correctness without adding
 * scheduler witnesses to the judgment.
 */
private fun EngineResult?.completedOutputDemand(): SelectionForest =
    when (this) {
        is ObjectEngineResult ->
            keys
                .filterNot { key -> key is ObjectEngineResult.ParentKey }
                .map { key ->
                    selectionForestOf(
                        Selection.of(
                            key = key,
                            possibleTypes = setOf(type),
                            subselections =
                                getCell(key)
                                    .getValue()
                                    .get()
                                    .completedOutputDemand(),
                        ),
                    )
                }.concatenateSelectionForests()
        is ListEngineResult ->
            indices
                .map { index -> get(index).getValue().get().completedOutputDemand() }
                .concatenateSelectionForests()
        else -> selectionForestOf()
    }

internal fun EngineObjectData.Sync?.requireArgumentlessField(
    key: ObjectEngineResult.ObjectKey,
) {
    if (this?.isPresent(key.field.name) == true) {
        require(key.field.args.isEmpty()) {
            "Resolver output must not supply argument-bearing field " +
                "${key.field.containingDef.name}/${key.field.name}"
        }
    }
}
