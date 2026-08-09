package semantics.resolver10

import model.Assumptions
import model.EngineResult
import model.Schema
import model.SelectionForest
import model.Value
import model.usedVariables
import model.registry.VariableDefinition
import model.registry.successorDemand
import model.registry.successorGroundBoundaryDemand
import semantics.ReactorEventObserver
import semantics.SelectionCompletion
import semantics.SelectionCompleter

/**
 * Resolves [selections] through exact readiness with runtime object-path variables.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object =
    resolve(
        selections = selections,
        eventObserver = {},
    )

context(world: Assumptions)
internal fun Value.Object.resolve(
    selections: SelectionForest,
    eventObserver: ReactorEventObserver = {},
): EngineResult.Object {
    val retainCompleteOutputs = world.hasObjectPathVariables()
    val selectionCompleter =
        SelectionCompleter { selections ->
            if (
                retainCompleteOutputs ||
                selections.usedVariables().isNotEmpty() ||
                selections.containsObjectPathResolverBoundary()
            ) {
                SelectionCompletion(
                    selections =
                        selections.successorGroundBoundaryDemand(),
                    selective = true,
                    retainCompleteOutput = true,
                )
            } else {
                SelectionCompletion(
                    selections = selections.successorDemand(),
                    selective = true,
                )
            }
        }
    return context(selectionCompleter) {
        Reactor(
            source = this@resolve,
            selections = selections,
            eventObserver = eventObserver,
        )
    }
}

private fun Assumptions.hasObjectPathVariables(): Boolean {
    val pending = ArrayDeque<Schema.ObjectType>()
    val visited = linkedSetOf<Schema.ObjectType>()
    pending += schema.query
    while (pending.isNotEmpty()) {
        val type = pending.removeFirst()
        if (!visited.add(type)) continue
        type.fields.values.forEach { field ->
            if (
                field in resolverRegistry &&
                resolverRegistry
                    .resolver(field)
                    .variables
                    .values
                    .any { definition ->
                        definition is VariableDefinition.FromObjectField
                    }
            ) {
                return true
            }
            when (val outputType = field.typeExpr.baseType) {
                is Schema.ObjectType -> pending += outputType
                is Schema.CompositeType -> pending += outputType.possibleTypes
                else -> Unit
            }
        }
    }
    return false
}

context(world: Assumptions)
private fun SelectionForest.containsObjectPathResolverBoundary(): Boolean {
    var found = false
    forEach { selection ->
        if (found) return@forEach
        found =
            selection.possibleTypes.any { possibleType ->
                val field = possibleType.fields.getValue(selection.key.field.fieldName)
                field.reachesObjectPathResolver()
            } ||
                selection.subselections.containsObjectPathResolverBoundary()
    }
    return found
}

context(world: Assumptions)
private fun model.Schema.ObjectField.reachesObjectPathResolver(
    visited: Set<model.Schema.ObjectField> = emptySet(),
): Boolean {
    if (this !in world.resolverRegistry || this in visited) return false
    val resolver = world.resolverRegistry.resolver(this)
    return resolver.variables.values.any { it is VariableDefinition.FromObjectField } ||
        world.resolverRegistry
            .mayDemandFrom(this)
            .any { field -> field.reachesObjectPathResolver(visited + this) }
}
