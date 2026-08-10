package semantics.resolver24

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.Assumptions
import model.EngineResult
import model.Schema
import model.SelectionForest
import model.Value
import model.usedVariables
import model.registry.VariableDefinition
import model.registry.successorDemand
import model.registry.successorGroundBoundaryDemand
import semantics.RuntimeSupport
import semantics.SelectionCompletion
import semantics.coroutineResolveOpen

/**
 * Resolves open demand through structured coroutines with runtime object-path variables.
 */
context(world: Assumptions)
fun Value.Object.resolve(selections: SelectionForest): EngineResult.Object {
    require(world.selectiveResolvers) {
        "Resolver24 requires selective resolvers"
    }
    val retainCompleteOutputs = world.hasObjectPathVariables()
    val runtimeSupport =
        RuntimeSupport.cycleChecking(
            RuntimeSupport { completedSelections ->
                if (
                    retainCompleteOutputs ||
                    completedSelections.usedVariables().isNotEmpty() ||
                    completedSelections.containsObjectPathResolverBoundary()
                ) {
                    SelectionCompletion(
                        selections =
                            completedSelections.successorGroundBoundaryDemand(),
                        retainCompleteOutput = true,
                    )
                } else {
                    SelectionCompletion(
                        selections = completedSelections.successorDemand(),
                    )
                }
            },
        )
    return runBlocking {
        withTimeout(90_000) {
            context(runtimeSupport) {
                this@resolve.coroutineResolveOpen(selections)
            }
        }
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
private fun Schema.ObjectField.reachesObjectPathResolver(
    visited: Set<Schema.ObjectField> = emptySet(),
): Boolean {
    if (this !in world.resolverRegistry || this in visited) return false
    val resolver = world.resolverRegistry.resolver(this)
    return resolver.variables.values.any { it is VariableDefinition.FromObjectField } ||
        world.resolverRegistry
            .mayDemandFrom(this)
            .any { field -> field.reachesObjectPathResolver(visited + this) }
}
