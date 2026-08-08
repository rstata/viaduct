package semantics

import model.Assumptions
import model.EngineResult
import model.PathComponent
import model.SelectionForest
import model.Value
import model.instantiateBindings
import model.merge
import model.selectionForestOf

/**
 * A passively populated partial result and the registered resolver work remaining within it.
 *
 * Each pending item identifies one exact object occurrence. The partial result contains references
 * to those OERs, allowing their cells to be populated after the parent cell has been written.
 */
internal class ResolvedValue(
    val partialValue: PartialValue?,
    val pending: List<ResolveOER>,
) {
    val engineResult: EngineResult?
        get() = partialValue.freeze()

    val pathsNeedingResolution: Map<List<PathComponent>, SelectionForest>
        get() = pending.associate { work -> work.oer.path to work.selections }
}

/**
 * Returns this output as a passive partial result together with every object requiring registered
 * field resolution for [resolverDemand].
 *
 * [beSelective] controls passive construction. A false value includes every passive field actually
 * present in the output, recursively stopping at registered resolver boundaries. A true value
 * includes only fields in [resolverDemand]. Null, error, and simple values terminate traversal.
 */
context(world: Assumptions)
internal fun Value.Output?.resolveValue(
    path: List<PathComponent>,
    resolverDemand: SelectionForest,
    beSelective: Boolean,
): ResolvedValue =
    when (this) {
        null -> ResolvedValue(null, emptyList())
        Value.Error -> ResolvedValue(PartialValue.Terminal(Value.Error), emptyList())
        is Value.Simple -> ResolvedValue(PartialValue.Terminal(this), emptyList())
        is Value.Object -> resolveObjectValue(resolverDemand, beSelective, path)
        is Value.OutputList -> {
            val elements =
                values.mapIndexed { index, value ->
                    value.resolveValue(
                        path = path + Value.ListIndex.of(index),
                        resolverDemand = resolverDemand,
                        beSelective = beSelective,
                    )
                }
            ResolvedValue(
                partialValue =
                    PartialValue.ListValue(
                        typeExpr = typeExpr,
                        cells =
                            elements.map { element ->
                                PartialCell(element.partialValue)
                            },
                    ),
                pending = elements.flatMap(ResolvedValue::pending),
            )
        }
    }

context(world: Assumptions)
private fun Value.Object.resolveObjectValue(
    resolverDemand: SelectionForest,
    beSelective: Boolean,
    path: List<PathComponent>,
): ResolvedValue {
    val mergedResolverDemand = resolverDemand.merge(type).instantiateBindings()
    val resolverDemandByKey = mergedResolverDemand.byGroundKey()
    if (world.selectiveResolvers && beSelective) {
        val unselectedKeys = fieldValues.keys - resolverDemandByKey.keys
        require(unselectedKeys.isEmpty()) {
            "Selective resolver output ${type.typeName} contains unselected fields: " +
                unselectedKeys.joinToString { key -> key.field.fieldName }
        }
    }

    val oer = PartialOER(path, this)
    val localPending =
        if (resolverDemandByKey.keys.any { key -> key.field in world.resolverRegistry }) {
            listOf(ResolveOER(oer, resolverDemand))
        } else {
            emptyList()
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
    val descendantPending =
        selectedKeys.flatMap { key ->
            if (key.field.fieldName == "__typename") {
                oer.write(
                    key,
                    PartialCell(
                        PartialValue.Terminal(Value.String.of(type.typeName)),
                    ),
                )
                emptyList()
            } else {
                val fieldValue =
                    fieldValues
                        .getValue(key)
                        .resolveValue(
                            path = path + key,
                            resolverDemand =
                                resolverDemandByKey[key]
                                    ?.subselections
                                    ?: selectionForestOf(),
                            beSelective = beSelective,
                        )
                oer.write(key, PartialCell(fieldValue.partialValue))
                fieldValue.pending
            }
        }

    return ResolvedValue(
        partialValue = PartialValue.ObjectReference(oer),
        pending = localPending + descendantPending,
    )
}
