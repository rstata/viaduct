package semantics

import viaduct.graphql.schema.ViaductSchema

import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.outputType
import model.outputValue
import model.PathComponent
import model.SelectionForest
import viaduct.engine.api.EngineObjectData
import model.applicableGroundSelections
import model.invariants.conformsToOutputSchemaType
import model.schemaType
import model.requireField
import model.selectionForestOf
import model.toEngineResult

/**
 * A passive result tree and the object occurrences requiring registered resolver work within it.
 *
 * Each member of [objectsNeedingResolution] retains the source object value, mutable result object,
 * exact root-relative OER path, and selection forest already collapsed to that occurrence.
 */
internal class ResolvePassiveValuesResult(
    val engineResult: EngineResult?,
    val objectsNeedingResolution: List<PassiveObjectOccurrence>,
    val objectOccurrences: List<PassiveObjectOccurrence>,
)

internal class PassiveObjectOccurrence(
    val path: List<PathComponent>,
    val source: EngineObjectData.Sync,
    val selections: SelectionForest,
    val target: ObjectEngineResult,
)

/**
 * Returns this output as a passive result tree together with every object path requiring registered
 * field resolution for [resolverDemand].
 *
 * Selective worlds include only fields in [resolverDemand]. Non-selective worlds include every
 * passive field actually present in the output and recursively stop at registered resolver
 * boundaries. Null, error, and simple values terminate traversal.
 */
context(world: Assumptions)
internal fun EngineOutputData?.resolvePassiveValues(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
): ResolvePassiveValuesResult {
    require(conformsToOutputSchemaType(expectedType)) {
        "Resolver output does not conform to $expectedType"
    }
    return when (this) {
        null -> ResolvePassiveValuesResult(null, emptyList(), emptyList())
        is EngineErrorData ->
            ResolvePassiveValuesResult(ErrorEngineResult.of(this), emptyList(), emptyList())
        is EngineObjectData.Sync -> resolvePassiveObjectValues(resolverDemand, path)
        is List<*> -> {
            val elementType = checkNotNull(expectedType.unwrapList())
            this
                .withIndex()
                .fold(
                    ResolvePassiveListValuesResult(
                        values = emptyList(),
                        objectsNeedingResolution = emptyList(),
                        objectOccurrences = emptyList(),
                    ),
                ) { resolved, (index, value) ->
                    val element =
                        value.resolvePassiveValues(
                            expectedType = elementType,
                            path = path + ListEngineResult.Index.of(index),
                            resolverDemand = resolverDemand,
                        )
                    ResolvePassiveListValuesResult(
                        values = resolved.values + element.engineResult,
                        objectsNeedingResolution =
                            resolved.objectsNeedingResolution +
                                element.objectsNeedingResolution,
                        objectOccurrences =
                            resolved.objectOccurrences +
                                element.objectOccurrences,
                    )
                }.let { resolved ->
                    ResolvePassiveValuesResult(
                        engineResult =
                            ListEngineResult.of(elementType, resolved.values),
                        objectsNeedingResolution = resolved.objectsNeedingResolution,
                        objectOccurrences = resolved.objectOccurrences,
                    )
                }
        }
        else ->
            ResolvePassiveValuesResult(
                toEngineResult(expectedType.baseTypeDef as ViaductSchema.SimpleTypeDef),
                emptyList(),
                emptyList(),
            )
    }
}

context(world: Assumptions)
private fun EngineObjectData.Sync.resolvePassiveObjectValues(
    resolverDemand: SelectionForest,
    path: List<PathComponent>,
): ResolvePassiveValuesResult {
    val mergedResolverDemand = resolverDemand.applicableGroundSelections(schemaType)
    val resolverDemandByKey = mergedResolverDemand.byGroundKey()
    if (world.selectiveResolvers) {
        val selectedFieldNames =
            resolverDemandByKey.keys.mapTo(linkedSetOf()) { key -> key.field.name }
        val unselectedKeys = getSelections().toSet() - selectedFieldNames
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${schemaType.name} contains unselected fields: " +
                unselectedKeys.joinToString()
        }
    }

    val selectedKeys =
        if (world.selectiveResolvers) {
            resolverDemandByKey.keys
                .filter { key -> key.field !in world.resolverRegistry }
                .toSet()
        } else {
            getSelections()
                .map { fieldName ->
                    val field = schemaType.requireField(fieldName)
                    require(field.args.isEmpty()) {
                        "Passive object field ${schemaType.name}/$fieldName must be argumentless"
                    }
                    ObjectEngineResult.GroundKey.of(field, emptyMap())
                }.filter { key -> key.field !in world.resolverRegistry }
                .toSet()
        }
    val resolved =
        selectedKeys.fold(
            ResolvePassiveObjectValuesResult(
                values = emptyMap(),
                objectsNeedingResolution = emptyList(),
                objectOccurrences = emptyList(),
            ),
        ) { result, key ->
            val arguments = key.arguments
            require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
                "Passive object field ${schemaType.name}/${key.field.name} must be argumentless"
            }
            val fieldValue =
                outputValue(key.field.name)
                    .resolvePassiveValues(
                        expectedType = key.field.outputType,
                        path = path + key,
                        resolverDemand =
                            resolverDemandByKey[key]
                                ?.subselections
                                ?: selectionForestOf(),
                    )
            ResolvePassiveObjectValuesResult(
                values = result.values + (key to fieldValue.engineResult),
                objectsNeedingResolution =
                    result.objectsNeedingResolution +
                        fieldValue.objectsNeedingResolution,
                objectOccurrences =
                    result.objectOccurrences +
                        fieldValue.objectOccurrences,
            )
        }
    val engineResult = ObjectEngineResult.of(schemaType, resolved.values, mutable = true)
    val localOccurrence =
        PassiveObjectOccurrence(
            path = path,
            source = this,
            selections = resolverDemand,
            target = engineResult,
        )
    val localResolution =
        if (resolverDemandByKey.keys.any { key -> key.field in world.resolverRegistry }) {
            listOf(localOccurrence)
        } else {
            emptyList()
        }
    return ResolvePassiveValuesResult(
        engineResult = engineResult,
        objectsNeedingResolution = localResolution + resolved.objectsNeedingResolution,
        objectOccurrences = listOf(localOccurrence) + resolved.objectOccurrences,
    )
}

/** Resolves the retained object occurrences deepest first without replacing any result value. */
internal fun ResolvePassiveValuesResult.resolveRetainedObjects(
    resolveObject: (PassiveObjectOccurrence) -> Unit,
): EngineResult? {
    objectsNeedingResolution
        .sortedByDescending { passiveObjectOccurrence -> passiveObjectOccurrence.path.size }
        .forEach(resolveObject)
    return engineResult
}

private class ResolvePassiveListValuesResult(
    val values: List<EngineResult?>,
    val objectsNeedingResolution: List<PassiveObjectOccurrence>,
    val objectOccurrences: List<PassiveObjectOccurrence>,
)

private class ResolvePassiveObjectValuesResult(
    val values: Map<ObjectEngineResult.GroundKey, EngineResult?>,
    val objectsNeedingResolution: List<PassiveObjectOccurrence>,
    val objectOccurrences: List<PassiveObjectOccurrence>,
)
