package semantics

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.SelectionForest
import model.Value
import model.applicableGroundSelections
import model.selectionForestOf
import model.toEngineResult

/**
 * A passive result tree and the object occurrences requiring registered resolver work within it.
 *
 * Each member of [objectsNeedingResolution] retains the source object value, mutable result object,
 * exact root-relative OER path, and selection forest already collapsed to that occurrence.
 */
internal class ResolvedValue(
    val engineResult: EngineResult?,
    val objectsNeedingResolution: List<ObjectResolution>,
    val objectOccurrences: List<ObjectResolution>,
)

internal class ObjectResolution(
    val path: List<PathComponent>,
    val source: Value.Object,
    val selections: SelectionForest,
    val target: ObjectEngineResult,
)

/**
 * Returns this output as a passive result tree together with every object path requiring registered
 * field resolution for [resolverDemand].
 *
 * Selective worlds include only fields in [resolverDemand]. Non-selective worlds include every
 * passive field actually present in the output, including resolver-supplied `__typename`, and
 * recursively stop at registered resolver boundaries. Null, error, and simple values terminate
 * traversal.
 */
context(world: Assumptions)
internal fun Value.Output?.resolveValue(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
): ResolvedValue =
    when (this) {
        null -> ResolvedValue(null, emptyList(), emptyList())
        Value.Error -> ResolvedValue(ErrorEngineResult, emptyList(), emptyList())
        is Value.Simple -> ResolvedValue(toEngineResult(), emptyList(), emptyList())
        is Value.Object -> resolveObjectValue(resolverDemand, path)
        is Value.OutputList ->
            values
                .withIndex()
                .fold(
                    ResolvedList(
                        values = emptyList(),
                        objectsNeedingResolution = emptyList(),
                        objectOccurrences = emptyList(),
                    ),
                ) { resolved, (index, value) ->
                    val element =
                        value.resolveValue(
                            path = path + ListEngineResult.Index.of(index),
                            resolverDemand = resolverDemand,
                        )
                    ResolvedList(
                        values = resolved.values + element.engineResult,
                        objectsNeedingResolution =
                            resolved.objectsNeedingResolution +
                                element.objectsNeedingResolution,
                        objectOccurrences =
                            resolved.objectOccurrences +
                                element.objectOccurrences,
                    )
                }.let { resolved ->
                    ResolvedValue(
                        engineResult = ListEngineResult.of(typeExpr, resolved.values),
                        objectsNeedingResolution = resolved.objectsNeedingResolution,
                        objectOccurrences = resolved.objectOccurrences,
                    )
                }
    }

context(world: Assumptions)
private fun Value.Object.resolveObjectValue(
    resolverDemand: SelectionForest,
    path: List<PathComponent>,
): ResolvedValue {
    val mergedResolverDemand = resolverDemand.applicableGroundSelections(type)
    val resolverDemandByKey = mergedResolverDemand.byGroundKey()
    if (world.selectiveResolvers) {
        val selectedFieldNames =
            resolverDemandByKey.keys.mapTo(linkedSetOf()) { key -> key.field.fieldName }
        val unselectedKeys = fieldValues.keys - selectedFieldNames
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${type.typeName} contains unselected fields: " +
                unselectedKeys.joinToString()
        }
    }

    val selectedKeys =
        if (world.selectiveResolvers) {
            resolverDemandByKey.keys
                .filter { key -> key.field !in world.resolverRegistry }
                .toSet()
        } else {
            fieldValues.keys
                .map { fieldName ->
                    val field = type.fields.getValue(fieldName)
                    require(field.arguments.fields.isEmpty()) {
                        "Passive object field ${type.typeName}/$fieldName must be argumentless"
                    }
                    ObjectEngineResult.GroundKey.of(field, emptyMap())
                }.filter { key -> key.field !in world.resolverRegistry }
                .toSet()
        }
    val resolved =
        selectedKeys.fold(
            ResolvedObject(
                values = emptyMap(),
                objectsNeedingResolution = emptyList(),
                objectOccurrences = emptyList(),
            ),
        ) { result, key ->
            val arguments = key.arguments
            require(arguments is Value.Arguments && arguments.fieldValues.isEmpty()) {
                "Passive object field ${type.typeName}/${key.field.fieldName} must be argumentless"
            }
            val fieldValue =
                fieldValues
                    .getValue(key.field.fieldName)
                    .resolveValue(
                        path = path + key,
                        resolverDemand =
                            resolverDemandByKey[key]
                                ?.subselections
                                ?: selectionForestOf(),
                    )
            ResolvedObject(
                values = result.values + (key to fieldValue.engineResult),
                objectsNeedingResolution =
                    result.objectsNeedingResolution +
                        fieldValue.objectsNeedingResolution,
                objectOccurrences =
                    result.objectOccurrences +
                        fieldValue.objectOccurrences,
            )
        }
    val engineResult = ObjectEngineResult.of(type, resolved.values, mutable = true)
    val localOccurrence =
        ObjectResolution(
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
    return ResolvedValue(
        engineResult = engineResult,
        objectsNeedingResolution = localResolution + resolved.objectsNeedingResolution,
        objectOccurrences = listOf(localOccurrence) + resolved.objectOccurrences,
    )
}

/** Resolves the retained object occurrences deepest first without replacing any result value. */
internal fun ResolvedValue.resolveObjects(resolveObject: (ObjectResolution) -> Unit): EngineResult? {
    objectsNeedingResolution
        .sortedByDescending { objectResolution -> objectResolution.path.size }
        .forEach(resolveObject)
    return engineResult
}

private class ResolvedList(
    val values: List<EngineResult?>,
    val objectsNeedingResolution: List<ObjectResolution>,
    val objectOccurrences: List<ObjectResolution>,
)

private class ResolvedObject(
    val values: Map<ObjectEngineResult.GroundKey, EngineResult?>,
    val objectsNeedingResolution: List<ObjectResolution>,
    val objectOccurrences: List<ObjectResolution>,
)
