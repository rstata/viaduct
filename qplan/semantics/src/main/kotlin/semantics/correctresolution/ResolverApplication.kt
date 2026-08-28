package semantics.correctresolution

import kotlinx.coroutines.runBlocking
import model.Arguments
import model.Assumptions
import model.EngineOutputData
import model.ObjectEngineResult
import model.PathComponent
import semantics.ResolverSupport
import semantics.materialize
import viaduct.engine.api.EngineObjectData
import java.util.IdentityHashMap

internal class ReappliedResolver(
    val output: EngineOutputData?,
)

internal class ResolverApplicationCache {
    private val applications =
        IdentityHashMap<
            ObjectEngineResult,
            MutableMap<ObjectEngineResult.GroundKey, CachedResolverApplication>,
        >()

    fun getOrPut(
        result: ObjectEngineResult,
        key: ObjectEngineResult.GroundKey,
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
    world: Assumptions,
    resolverSupport: ResolverSupport,
    resolverApplicationCache: ResolverApplicationCache,
)
internal fun ObjectEngineResult.reapplyResolver(
    key: ObjectEngineResult.GroundKey,
    path: List<PathComponent>,
): ReappliedResolver? =
    resolverApplicationCache.getOrPut(this, key) {
        val arguments = key.arguments as? Arguments.Resolved ?: return@getOrPut null
        val resolver = world.resolverRegistry.resolver(key.field)
        val coordinate = path + key
        val objectFragment =
            resolver.objectFragmentSatisfiedBy(
                result = this,
                path = coordinate,
            ) ?: return@getOrPut null
        val input: EngineObjectData.Sync =
            runBlocking {
                materialize(
                    selections = objectFragment.materializeSelections,
                    reader = coordinate,
                )
            }
        val resolverArguments =
            Arguments.Resolved.of(
                field = key.field,
                fields = arguments.fieldValues,
            )
        ReappliedResolver(resolver.evaluateRelation(input, resolverArguments))
    }

internal fun EngineObjectData.Sync?.requireArgumentlessField(
    key: ObjectEngineResult.GroundKey,
) {
    if (this?.isPresent(key.field.name) == true) {
        require(key.field.args.isEmpty()) {
            "Resolver output must not supply argument-bearing field " +
                "${key.field.containingDef.name}/${key.field.name}"
        }
    }
}
