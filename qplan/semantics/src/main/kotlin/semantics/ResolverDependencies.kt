package semantics

import model.Assumptions
import model.EngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.Value
import model.applicableGroundSelections

/**
 * Returns the exact resolver-instance coordinates currently required by each local slot.
 *
 * Recompute this map as active ancestors publish object and list shape. A field-resolver slot may
 * therefore gain deeper exact dependencies over time.
 */
context(world: Assumptions)
internal fun EngineResult.Object.resolverDependencies(
    path: List<PathComponent>,
    selections: ObjectSelectionForest,
    completedResolverCoordinates: Set<List<PathComponent>>,
): Map<Value.GroundKey, Set<List<PathComponent>>> =
    selections.byGroundKey().mapValues { (key, _) ->
        if (key.reactorSlotKind() == ReactorSlotKind.FIELD_RESOLVER) {
            ResolverDependencyWalk(completedResolverCoordinates)
                .dependencies(
                    objectPath = path,
                    target = this,
                    selections =
                        world.resolverRegistry
                            .resolver(key.field)
                            .stampVars(path + key),
                )
        } else {
            emptySet()
        }
    }

private class ResolverDependencyWalk(
    private val completedResolverCoordinates: Set<List<PathComponent>>,
) {
    private val dependencies = linkedSetOf<List<PathComponent>>()

    context(world: Assumptions)
    fun dependencies(
        objectPath: List<PathComponent>,
        target: EngineResult.Object,
        selections: SelectionForest,
    ): Set<List<PathComponent>> {
        walkObject(objectPath, target, selections)
        return dependencies.toSet()
    }

    context(world: Assumptions)
    private fun walkObject(
        objectPath: List<PathComponent>,
        target: EngineResult.Object,
        selections: SelectionForest,
    ) {
        val applicable = selections.applicableGroundSelections(target.type)
        applicable.byGroundKey().forEach { (key, selection) ->
            val coordinate = objectPath + key
            when (key.reactorSlotKind()) {
                ReactorSlotKind.ENGINE_OWNED,
                ReactorSlotKind.PASSIVE,
                -> {
                    if (!target.isValueSet(key)) {
                        dependencies += coordinate
                    } else {
                        walkValue(
                            path = coordinate,
                            value = target.getValue(key).get(),
                            selections = selection.subselections,
                        )
                    }
                }

                ReactorSlotKind.FIELD_RESOLVER -> {
                    dependencies += coordinate
                    if (coordinate in completedResolverCoordinates) {
                        check(target.isValueSet(key)) {
                            "Completed resolver has no published value: " +
                                coordinate.renderReactorPath()
                        }
                        walkValue(
                            path = coordinate,
                            value = target.getValue(key).get(),
                            selections = selection.subselections,
                        )
                    }
                }
            }
        }
    }

    context(world: Assumptions)
    private fun walkValue(
        path: List<PathComponent>,
        value: EngineResult?,
        selections: SelectionForest,
    ) {
        when (value) {
            null,
            Value.Error,
            is Value.Simple,
            -> Unit

            is EngineResult.Object ->
                walkObject(
                    objectPath = path,
                    target = value,
                    selections = selections,
                )

            is EngineResult.List ->
                value.forEachIndexed { index, element ->
                    walkValue(
                        path = path + Value.ListIndex.of(index),
                        value = element,
                        selections = selections,
                    )
                }
        }
    }
}
