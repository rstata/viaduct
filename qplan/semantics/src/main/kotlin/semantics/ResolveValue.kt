package semantics

import model.Assumptions
import model.EngineResult
import model.PathComponent
import model.SelectionForest
import model.Value
import model.applicableGroundSelections
import model.selectionForestOf

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
    val target: EngineResult.Object,
)

/**
 * Returns this output as a passive result tree together with every object path requiring registered
 * field resolution for [resolverDemand].
 *
 * Selective worlds include only fields in [resolverDemand]. Non-selective worlds, and boundaries
 * where [retainCompleteOutput] is true, include every passive field actually present in the output,
 * recursively stopping at registered resolver boundaries. Null, error, and simple values terminate
 * traversal.
 */
context(world: Assumptions)
internal fun Value.Output?.resolveValue(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
    retainCompleteOutput: Boolean = false,
): ResolvedValue =
    when (this) {
        null -> ResolvedValue(null, emptyList(), emptyList())
        Value.Error -> ResolvedValue(Value.Error, emptyList(), emptyList())
        is Value.Simple -> ResolvedValue(this, emptyList(), emptyList())
        is Value.Object -> resolveObjectValue(resolverDemand, retainCompleteOutput, path)
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
                            path = path + Value.ListIndex.of(index),
                            resolverDemand = resolverDemand,
                            retainCompleteOutput = retainCompleteOutput,
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
                        engineResult = EngineResult.List.of(typeExpr, resolved.values),
                        objectsNeedingResolution = resolved.objectsNeedingResolution,
                        objectOccurrences = resolved.objectOccurrences,
                    )
                }
    }

context(world: Assumptions)
private fun Value.Object.resolveObjectValue(
    resolverDemand: SelectionForest,
    retainCompleteOutput: Boolean,
    path: List<PathComponent>,
): ResolvedValue {
    val mergedResolverDemand = resolverDemand.applicableGroundSelections(type)
    val resolverDemandByKey = mergedResolverDemand.byGroundKey()
    val selectOutput = world.selectiveResolvers && !retainCompleteOutput
    if (selectOutput) {
        val unselectedKeys =
            fieldValues.keys.filterNot { key -> key.field.fieldName == "__typename" } -
                resolverDemandByKey.keys
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${type.typeName} contains unselected fields: " +
                unselectedKeys.joinToString { key -> key.field.fieldName }
        }
    }

    val selectedKeys =
        if (selectOutput) {
            resolverDemandByKey.keys
                .filter { key -> key.field !in world.resolverRegistry }
                .toSet()
        } else {
            fieldValues.keys.filter { key -> !world.behavioral(key.field) }.toSet() +
                resolverDemandByKey.keys.filter { key ->
                    key.field.fieldName == "__typename"
                }
        }
    val resolved =
        selectedKeys.fold(
            ResolvedObject(
                values = emptyMap(),
                objectsNeedingResolution = emptyList(),
                objectOccurrences = emptyList(),
            ),
        ) { result, key ->
            val fieldValue =
                fieldValues
                    .getValue(key)
                    .resolveValue(
                        path = path + key,
                        resolverDemand =
                            resolverDemandByKey[key]
                                ?.subselections
                                ?: selectionForestOf(),
                        retainCompleteOutput = retainCompleteOutput,
                    )
            ResolvedObject(
                values =
                    result.values +
                        (key to fieldValue.engineResult),
                objectsNeedingResolution =
                    result.objectsNeedingResolution +
                        fieldValue.objectsNeedingResolution,
                objectOccurrences =
                    result.objectOccurrences +
                        fieldValue.objectOccurrences,
            )
        }
    val engineResult = EngineResult.Object.of(type, resolved.values, mutable = true)
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
    val values: Map<Value.GroundKey, EngineResult?>,
    val objectsNeedingResolution: List<ObjectResolution>,
    val objectOccurrences: List<ObjectResolution>,
)
