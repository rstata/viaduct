package semantics

import model.Assumptions
import model.EngineResult
import model.ObjectSelection
import model.PathComponent
import model.SelectionForest
import model.Value
import model.groundKey
import model.instantiateBindings
import model.merge
import model.registry.demandsFromSibling
import semantics.correctresolution.argumentsContainErrorValue

internal data class SelectionCompletion(
    val selections: SelectionForest,
    val selective: Boolean,
)

/**
 * SPI for suplying the output-boundary policy for one resolution constructor.
 *
 * [complete] expands the selections visible at a resolver output boundary and indicates whether
 * the resolver result and passive traversal are selective to those completed selections.  Instances
 * of these are passed as context arguments to control how resolution works.
 */
internal fun interface SelectionCompleter {
    context(world: Assumptions)
    fun complete(selections: SelectionForest): SelectionCompletion
}

/**
 * One LIFO work item for an exact object occurrence.
 *
 * A new item carries [selections]. Once prepared, [remaining] retains the dependency-ordered local
 * work so demand closure and argument binding are not repeated when descendants suspend this item.
 */
internal data class ResolveOER(
    val oer: PartialOER,
    val selections: SelectionForest,
    val remaining: List<ObjectSelection>? = null,
)

private data class KeyResolution(
    val key: Value.GroundKey,
    val cell: PartialCell,
    val children: List<ResolveOER>,
)

/**
 * Resolves [selections] with a LIFO worklist over write-once partial OERs, then freezes the root.
 *
 * Descendant items are pushed ahead of the suspended containing OER, preserving the existing
 * dependency-first depth-first order without recursive object resolution or immutable path repair.
 */
context(world: Assumptions, selectionCompleter: SelectionCompleter)
internal fun Value.Object.resolve(
    path: List<PathComponent>,
    selections: SelectionForest,
    resolved: EngineResult.Object,
): EngineResult.Object {
    require(resolved.type == type) {
        "Initial result type ${resolved.type.typeName} does not match $type"
    }
    require(resolved.keys.isEmpty()) {
        "LIFO resolution requires an initially empty root result"
    }

    val root = PartialOER(path, this)
    val work = ArrayDeque<ResolveOER>()
    work.addFirst(ResolveOER(root, selections))

    while (work.isNotEmpty()) {
        val current = work.removeFirst().prepare()
        val fieldSelection = current.remaining?.firstOrNull() ?: continue
        val resolution =
            current.oer.source.resolveKey(
                path = current.oer.path,
                fieldSelection = fieldSelection,
                resolved = current.oer,
            )
        current.oer.write(resolution.key, resolution.cell)

        val remaining = current.remaining.drop(1)
        if (remaining.isNotEmpty()) {
            work.addFirst(current.copy(remaining = remaining))
        }
        resolution.children
            .sortedByDescending { child -> child.oer.path.size }
            .asReversed()
            .forEach(work::addFirst)
    }

    return root.freeze()
}

context(world: Assumptions)
private fun ResolveOER.prepare(): ResolveOER {
    if (remaining != null) return this

    val closedDemand = oer.type.closeResolverDemand(oer.path, selections)
    val unresolvedKeys = closedDemand.groundKeys() - oer.keys
    val orderedKeys = oer.source.dependencyOrder(oer.path, unresolvedKeys)
    return copy(
        remaining = orderedKeys.map { key -> closedDemand[key] },
    )
}

/**
 * Returns a topological ordering of [keys] using Kahn's algorithm, with demand before consumption.
 */
context(world: Assumptions)
private fun Value.Object.dependencyOrder(
    path: List<PathComponent>,
    keys: Set<Value.GroundKey>,
    ordered: List<Value.GroundKey> = emptyList(),
): List<Value.GroundKey> {
    if (keys.isEmpty()) return ordered

    val ready =
        keys.filter { key ->
            dependenciesOf(path, key, keys).isEmpty()
        }.toSet()
    require(ready.isNotEmpty()) {
        "Resolver dependencies on ${type.typeName} contain a cycle"
    }
    return dependencyOrder(
        path = path,
        keys = keys - ready,
        ordered = ordered + ready,
    )
}

/** Returns the unresolved sibling keys demanded by the field resolver for [consumer]. */
context(world: Assumptions)
private fun Value.Object.dependenciesOf(
    path: List<PathComponent>,
    consumer: Value.GroundKey,
    unresolved: Set<Value.GroundKey>,
): Set<Value.GroundKey> {
    if (
        consumer.arguments.argumentsContainErrorValue() ||
        consumer.field !in world.resolverRegistry
    ) {
        return emptySet()
    }

    return unresolved
        .filter { sibling ->
            sibling != consumer &&
                consumer.demandsFromSibling(sibling, path + consumer)
        }.toSet()
}

/** Returns one write-once cell and the descendant OER work exposed by its value. */
context(world: Assumptions, selectionCompleter: SelectionCompleter)
private fun Value.Object.resolveKey(
    path: List<PathComponent>,
    fieldSelection: ObjectSelection,
    resolved: PartialOER,
): KeyResolution {
    val key = fieldSelection.groundKey()
    if (key.arguments.argumentsContainErrorValue()) {
        return KeyResolution(key, PartialCell.Error, emptyList())
    }
    if (key.field.fieldName == "__typename") {
        return KeyResolution(
            key = key,
            cell =
                PartialCell(
                    PartialValue.Terminal(Value.String.of(type.typeName)),
                ),
            children = emptyList(),
        )
    }

    val completion = selectionCompleter.complete(fieldSelection.subselections)
    val resolutionSelections = completion.selections
    val fieldValue =
        if (key.field in world.resolverRegistry) {
            val resolver = world.resolverRegistry.resolver(key.field)
            val objectFragment =
                resolver
                    .stampedObjectFragment(path + key)
                    .merge(type)
                    .instantiateBindings()
            val input = resolved.materialize(objectFragment)
            if (completion.selective) {
                resolver(
                    input = input,
                    arguments = key.arguments,
                    selections = resolutionSelections,
                )
            } else {
                resolver(
                    input = input,
                    arguments = key.arguments,
                )
            }
        } else {
            require(!completion.selective) {
                "Passive key found ($key)."
            }
            fieldValues.getValue(key)
        }
    val resolvedValue =
        fieldValue.resolveValue(
            path = path + key,
            resolverDemand = resolutionSelections,
            beSelective = completion.selective,
        )
    return KeyResolution(
        key = key,
        cell = PartialCell(resolvedValue.partialValue),
        children = resolvedValue.pending,
    )
}
