package semantics

import model.Assumptions
import model.EngineResult
import model.PathComponent
import model.SelectionForest
import model.Value
import model.mergeToGround
import model.selectionForestOf

/**
 * A passive result tree and the registered resolver work remaining within it.
 *
 * Each key in [pathsNeedingResolution] is the exact root-relative OER path of one object occurrence
 * requiring registered field resolution. Object fields contribute [Value.GroundKey] components and
 * list positions contribute [Value.ListIndex] components. Each map value is the selection forest
 * already collapsed to the object at that path.
 */
class ResolvedValue(
    val engineResult: EngineResult?,
    val pathsNeedingResolution: Map<List<PathComponent>, SelectionForest>,
)

/**
 * Returns this output as a passive result tree together with every object path requiring registered
 * field resolution for [resolverDemand].
 *
 * [beSelective] controls passive construction. A false value includes every passive field actually
 * present in the output, recursively stopping at registered resolver boundaries. A true value
 * includes only fields in [resolverDemand]. The demand also identifies registered boundaries that
 * must be resumed after passive construction. When [Assumptions.selectiveResolvers] is true,
 * selective construction requires every supplied object field to be demanded.
 *
 * [path] is the exact OER path from the Query root to this value. Selected `__typename` fields are
 * supplied directly from the concrete object type. Null, error, and simple values terminate
 * traversal, while object fields and list elements extend the path with their exact coordinates.
 */
context(world: Assumptions)
internal fun Value.Output?.resolveValue(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
    beSelective: Boolean,
): ResolvedValue =
    when (this) {
        null -> ResolvedValue(null, emptyMap())
        Value.Error -> ResolvedValue(Value.Error, emptyMap())
        is Value.Simple -> ResolvedValue(this, emptyMap())
        is Value.Object -> resolveObjectValue(resolverDemand, beSelective, path)
        is Value.OutputList ->
            values
                .withIndex()
                .fold(
                    ResolvedList(
                        cells = emptyList(),
                        pathsNeedingResolution = emptyMap(),
                    ),
                ) { resolved, (index, value) ->
                    val element =
                        value.resolveValue(
                            path = path + Value.ListIndex.of(index),
                            resolverDemand = resolverDemand,
                            beSelective = beSelective,
                        )
                    ResolvedList(
                        cells =
                            resolved.cells +
                                EngineResult.Cell.of(element.engineResult),
                        pathsNeedingResolution =
                            resolved.pathsNeedingResolution +
                                element.pathsNeedingResolution,
                    )
                }.let { resolved ->
                    ResolvedValue(
                        engineResult = EngineResult.List.of(typeExpr, resolved.cells),
                        pathsNeedingResolution = resolved.pathsNeedingResolution,
                    )
                }
    }

context(world: Assumptions)
private fun Value.Object.resolveObjectValue(
    resolverDemand: SelectionForest,
    beSelective: Boolean,
    path: List<PathComponent>,
): ResolvedValue {
    val mergedResolverDemand = resolverDemand.mergeToGround(type)
    val resolverDemandByKey = mergedResolverDemand.byKey()
    if (world.selectiveResolvers && beSelective) {
        val unselectedKeys = fieldValues.keys - resolverDemandByKey.keys
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${type.typeName} contains unselected fields: " +
                unselectedKeys.joinToString { key -> key.field.fieldName }
        }
    }
    val hasLocalResolverSelection =
        resolverDemandByKey.keys.any { key -> key.field in world.resolverRegistry }
    val localPaths =
        if (hasLocalResolverSelection) {
            mapOf(path to resolverDemand)
        } else {
            emptyMap()
        }
    val selectedKeys =
        if (beSelective) {
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
        selectedKeys
            .fold(
                ResolvedObject(
                    cells = emptyMap(),
                    pathsNeedingResolution = localPaths,
                ),
            ) { result, key ->
                if (key.field.fieldName == "__typename") {
                    ResolvedObject(
                        cells =
                            result.cells +
                                (
                                    key to
                                        EngineResult.Cell.of(
                                            Value.String.of(type.typeName),
                                        )
                                ),
                        pathsNeedingResolution = result.pathsNeedingResolution,
                    )
                } else {
                    val value = fieldValues.getValue(key)
                    val fieldValue =
                        value.resolveValue(
                            path = path + key,
                            resolverDemand =
                                resolverDemandByKey[key]
                                    ?.subselections
                                    ?: selectionForestOf(),
                            beSelective = beSelective,
                        )
                    ResolvedObject(
                        cells =
                            result.cells +
                                (key to EngineResult.Cell.of(fieldValue.engineResult)),
                        pathsNeedingResolution =
                            result.pathsNeedingResolution +
                                fieldValue.pathsNeedingResolution,
                    )
                }
            }
    return ResolvedValue(
        engineResult = EngineResult.Object.of(type, resolved.cells),
        pathsNeedingResolution = resolved.pathsNeedingResolution,
    )
}

/**
 * Resolves every path in [resolvedValue], deepest paths first, using [resolveObject].
 */
internal fun Value.Output?.resolvePaths(
    path: List<PathComponent>,
    resolvedValue: ResolvedValue,
    resolveObject:
        (
            path: List<PathComponent>,
            value: Value.Object,
            selections: SelectionForest,
            resolved: EngineResult.Object,
        ) -> EngineResult.Object,
): EngineResult? =
    resolvedValue.pathsNeedingResolution
        .entries
        .sortedByDescending { (path, _) -> path.size }
        .fold(resolvedValue.engineResult) { result, (targetPath, selections) ->
            require(
                targetPath.size >= path.size &&
                    targetPath.take(path.size) == path,
            ) {
                "Resolution target is not beneath its output root"
            }
            resolvePath(
                resolved = result,
                path = path,
                targetPath = targetPath,
                selections = selections,
                resolveObject = resolveObject,
            )
        }

private fun Value.Output?.resolvePath(
    resolved: EngineResult?,
    path: List<PathComponent>,
    targetPath: List<PathComponent>,
    selections: SelectionForest,
    resolveObject:
        (
            path: List<PathComponent>,
            value: Value.Object,
            selections: SelectionForest,
            resolved: EngineResult.Object,
        ) -> EngineResult.Object,
): EngineResult? {
    require(
        targetPath.size >= path.size &&
            targetPath.take(path.size) == path,
    ) {
        "Resolution target is not beneath the current output value"
    }
    return when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> resolved

        is Value.OutputList -> {
            require(resolved is EngineResult.List && resolved.size == values.size) {
                "Resolved list does not match its source output"
            }
            val index = targetPath.getOrNull(path.size) as? Value.ListIndex
                ?: throw IllegalArgumentException("Resolution path does not select a list element")
            require(index.index in values.indices) {
                "Resolution list index ${index.index} is absent"
            }
            EngineResult.List.of(
                typeExpr = resolved.typeExpr,
                cells =
                    values.indices.map { elementIndex ->
                        val cell = resolved[elementIndex]
                        if (elementIndex == index.index) {
                            EngineResult.Cell.of(
                                value =
                                    values[elementIndex].resolvePath(
                                        resolved = cell.value,
                                        path = path + index,
                                        targetPath = targetPath,
                                        selections = selections,
                                        resolveObject = resolveObject,
                                    ),
                                check = cell.check,
                            )
                        } else {
                            cell
                        }
                    },
            )
        }

        is Value.Object -> {
            require(resolved is EngineResult.Object && resolved.type == type) {
                "Resolved object does not match its source output"
            }
            if (path == targetPath) {
                resolveObject(path, this, selections, resolved)
            } else {
                val key = targetPath.getOrNull(path.size) as? Value.GroundKey
                    ?: throw IllegalArgumentException(
                        "Resolution path does not select an object field",
                    )
                require(key.field.containingType == type) {
                    "Resolution path field does not belong to ${type.typeName}"
                }
                require(key in fieldValues && key in resolved.keys) {
                    "Resolution path is absent from ${type.typeName}"
                }
                val existing = resolved.fetch(key)
                val fieldValue =
                    fieldValues
                        .getValue(key)
                        .resolvePath(
                            resolved = existing.value,
                            path = path + key,
                            targetPath = targetPath,
                            selections = selections,
                            resolveObject = resolveObject,
                        )
                EngineResult.Object.of(
                    type = type,
                    cells =
                        resolved.cells +
                            (
                                key to
                                    EngineResult.Cell.of(
                                        value = fieldValue,
                                        check = existing.check,
                                    )
                            ),
                )
            }
        }
    }
}

private class ResolvedList(
    val cells: List<EngineResult.Cell>,
    val pathsNeedingResolution: Map<List<PathComponent>, SelectionForest>,
)

private class ResolvedObject(
    val cells: Map<Value.GroundKey, EngineResult.Cell>,
    val pathsNeedingResolution: Map<List<PathComponent>, SelectionForest>,
)
