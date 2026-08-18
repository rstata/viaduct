package semantics.correctresolution

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.Schema
import model.SimpleEngineResult
import model.Stamp
import model.Value
import model.applicableGroundSelections
import model.toValue
import model.usedVariables
import model.registry.FieldResolver
import model.registry.ResolverObjectFragment
import semantics.RuntimeSupport
import semantics.materialize

/**
 * Whether every activated resolver agrees with the values attributed to it in this result tree.
 *
 * Field resolvers receive the containing object materialized according to their object fragment.
 * Each OER value is compared as a positional subset of the resolver's complete finite output.
 * Comparison preserves list positions and stops before coordinates supplied by another resolver or
 * by the engine. The complete OER tree is still traversed, so those coordinates are checked by
 * their own resolvers.
 *
 * This predicate assumes [isClosedUnderResolverDemand] has established that every resolver input
 * value is present. It observes cell values but never access-acceptance results.
 */
context(world: Assumptions)
fun ObjectEngineResult.conformsToResolvers(): Boolean =
    context(RuntimeSupport.noCycleChecking()) {
        objectConformsToResolvers(emptyList())
    }

context(world: Assumptions, runtimeSupport: RuntimeSupport)
private fun ObjectEngineResult.objectConformsToResolvers(
    path: List<PathComponent>,
): Boolean {
    val registry = world.resolverRegistry
    return keys.all { groundKey ->
        val value = getCell(groundKey).getValue().get()
        val arguments = groundKey.arguments
        val fieldResolverConforms =
            if (
                arguments !is Value.Arguments ||
                groundKey.field !in registry
            ) {
                true
            } else {
                val resolver = registry.resolver(groundKey.field)
                val coordinate = path + groundKey
                val objectFragment =
                    resolver.objectFragmentSatisfiedBy(
                        result = this,
                        path = coordinate,
                    ) ?: return false
                val input =
                    runBlocking {
                        materialize(
                            selections = objectFragment.materializeSelections,
                            reader = coordinate,
                        )
                    }
                val resolverArguments =
                    Value.Arguments.of(
                        field = groundKey.field,
                        fields = arguments.fieldValues,
                    )
                val resolverValue = resolver(input, resolverArguments)
                value.engineResultConformsToResolverValue(resolverValue)
            }

        fieldResolverConforms && value.engineResultConformsToResolvers(path + groundKey)
    }
}

context(world: Assumptions)
private fun FieldResolver.objectFragmentSatisfiedBy(
    result: ObjectEngineResult,
    path: List<PathComponent>,
): ResolverObjectFragment? {
    val groundKey = path.lastOrNull() as? ObjectEngineResult.GroundKey
    val selectionStamp = groundKey?.stamp as? Stamp.Occurrence
    val candidates =
        if (selectionStamp != null) {
            listOf(instantiateObjectFragment(selectionStamp))
        } else {
            listOf(
                instantiateObjectFragment(Stamp.Occurrence.of(resolverPath = path)),
                instantiateObjectFragmentAt(path),
            )
        }
    return candidates.firstOrNull { objectFragment ->
        val constructionSelections = objectFragment.constructionSelections
        constructionSelections.usedVariables().all { variable ->
            variable.isStamped && world.isBound(variable)
        } &&
            result.conformsToSelectionsAt(
                selections =
                    constructionSelections.applicableGroundSelections(field.containingType),
                path = path.dropLast(1),
            )
    }
}

context(world: Assumptions, runtimeSupport: RuntimeSupport)
private fun EngineResult?.engineResultConformsToResolvers(
    path: List<PathComponent>,
): Boolean =
    when (this) {
        null,
        ErrorEngineResult,
        is SimpleEngineResult,
        -> true

        is ObjectEngineResult -> objectConformsToResolvers(path)
        is ListEngineResult ->
            indices.all { index ->
                get(index).getValue().get().engineResultConformsToResolvers(
                    path + ListEngineResult.Index.of(index),
                )
            }
    }

context(world: Assumptions)
private fun EngineResult?.engineResultConformsToResolverValue(
    resolverValue: Value.Output?,
): Boolean =
    when (this) {
        null -> resolverValue == null
        ErrorEngineResult -> resolverValue == Value.Error
        is SimpleEngineResult -> toValue() == resolverValue

        is ObjectEngineResult ->
            resolverValue != Value.Error &&
                resolverValue is Value.Object &&
                objectFieldsConformToResolverValue(
                    resolverValue = resolverValue,
                    fieldBelongsToResolver = { field ->
                        field !in world.resolverRegistry
                    },
                )

        is ListEngineResult ->
            resolverValue != Value.Error &&
            resolverValue is Value.OutputList &&
                size == resolverValue.values.size &&
                indices.all { index ->
                    get(index).getValue().get().engineResultConformsToResolverValue(
                        resolverValue.values[index],
                    )
                }
    }

context(world: Assumptions)
private fun ObjectEngineResult.objectFieldsConformToResolverValue(
    resolverValue: Value.Object,
    fieldBelongsToResolver: (Schema.ObjectField) -> Boolean,
): Boolean {
    if (type != resolverValue.type) return false

    return keys.all { groundKey ->
        val fieldName = groundKey.field.fieldName
        val arguments = groundKey.arguments
        if (!fieldBelongsToResolver(groundKey.field)) {
            true
        } else if (
            arguments !is Value.Arguments ||
                arguments.fieldValues.isNotEmpty() ||
                !resolverValue.fieldValues.containsKey(fieldName)
        ) {
            false
        } else {
            getCell(groundKey).getValue().get().engineResultConformsToResolverValue(
                resolverValue.fieldValues.getValue(fieldName),
            )
        }
    }
}
