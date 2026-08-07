package semantics

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.merge
import model.selectionForestOf

/**
 * A passive result tree and the registered resolver work remaining within it.
 *
 * Each key in [pathsNeedingResolution] is a path of concrete object-field coordinates relative to
 * [engineResult]. Lists are transparent to paths: one path denotes the corresponding object
 * occurrence in every list position where that path exists. Each map value is the selection forest
 * already collapsed to the object at that path. These are resolver-reconstruction paths, not exact
 * OER occurrence paths; they intentionally omit [Value.ListIndex].
 */
class ResolvedValue(
    val engineResult: EngineResult?,
    val pathsNeedingResolution: Map<List<Value.ObjectKey>, SelectionForest>,
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
 * [path] is the concrete object-field path from the root output to this value. Selected
 * `__typename` fields are supplied directly from the concrete object type. Null, error, and simple
 * values terminate traversal, while lists preserve positions and apply the same object-field path
 * to every element.
 */
context(world: Assumptions)
internal fun Value.Output?.resolveValue(
    resolverDemand: SelectionForest,
    beSelective: Boolean,
    path: List<Value.ObjectKey> = emptyList(),
): ResolvedValue =
    when (this) {
        null -> ResolvedValue(null, emptyMap())
        Value.Error -> ResolvedValue(Value.Error, emptyMap())
        is Value.Simple -> ResolvedValue(this, emptyMap())
        is Value.Object -> resolveObjectValue(resolverDemand, beSelective, path)
        is Value.OutputList ->
            values
                .fold(
                    ResolvedList(
                        cells = emptyList(),
                        pathsNeedingResolution = emptyMap(),
                    ),
                ) { resolved, value ->
                    val element =
                        value.resolveValue(
                            resolverDemand = resolverDemand,
                            beSelective = beSelective,
                            path = path,
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
    path: List<Value.ObjectKey>,
): ResolvedValue {
    val mergedResolverDemand = resolverDemand.merge(type)
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
                            resolverDemand =
                                resolverDemandByKey[key]
                                    ?.subselections
                                    ?: selectionForestOf(),
                            beSelective = beSelective,
                            path = path + key,
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
    resolvedValue: ResolvedValue,
    resolveObject:
        (
            value: Value.Object,
            selections: SelectionForest,
            resolved: EngineResult.Object,
        ) -> EngineResult.Object,
): EngineResult? =
    resolvedValue.pathsNeedingResolution
        .entries
        .sortedByDescending { (path, _) -> path.size }
        .fold(resolvedValue.engineResult) { result, (path, selections) ->
            resolvePath(
                resolved = result,
                path = path,
                selections = selections,
                resolveObject = resolveObject,
            )
        }

private fun Value.Output?.resolvePath(
    resolved: EngineResult?,
    path: List<Value.ObjectKey>,
    selections: SelectionForest,
    resolveObject:
        (
            value: Value.Object,
            selections: SelectionForest,
            resolved: EngineResult.Object,
        ) -> EngineResult.Object,
): EngineResult? =
    when (this) {
        null,
        Value.Error,
        is Value.Simple,
        -> resolved

        is Value.OutputList -> {
            require(resolved is EngineResult.List && resolved.size == values.size) {
                "Resolved list does not match its source output"
            }
            EngineResult.List.of(
                typeExpr = resolved.typeExpr,
                cells =
                    values.indices.map { index ->
                        val cell = resolved[index]
                        EngineResult.Cell.of(
                            value =
                                values[index].resolvePath(
                                    resolved = cell.value,
                                    path = path,
                                    selections = selections,
                                    resolveObject = resolveObject,
                                ),
                            check = cell.check,
                        )
                    },
            )
        }

        is Value.Object -> {
            require(resolved is EngineResult.Object && resolved.type == type) {
                "Resolved object does not match its source output"
            }
            if (path.isEmpty()) {
                resolveObject(this, selections, resolved)
            } else {
                val key = path.first()
                if (key.field.containingType != type) {
                    resolved
                } else {
                    require(key in fieldValues && key in resolved.keys) {
                        "Resolution path is absent from ${type.typeName}"
                    }
                    val existing = resolved.fetch(key)
                    val fieldValue =
                        fieldValues
                            .getValue(key)
                            .resolvePath(
                                resolved = existing.value,
                                path = path.drop(1),
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
    val pathsNeedingResolution: Map<List<Value.ObjectKey>, SelectionForest>,
)

private class ResolvedObject(
    val cells: Map<Value.ObjectKey, EngineResult.Cell>,
    val pathsNeedingResolution: Map<List<Value.ObjectKey>, SelectionForest>,
)
