package model.testing

import model.Arguments
import model.Schema
import model.Selection
import model.SelectionForest
import model.registry.FieldResolver
import model.registry.VariableDefinition
import model.variables
import model.requireField

/**
 * Validates the argument-insensitive structural branch order before semantic reasoning begins.
 */
internal class BranchOrderValidator(
    private val fieldResolvers: Map<Schema.Field, FieldResolver>,
) {
    private data class Edge(
        val prerequisite: Schema.ObjectField,
        val consumer: Schema.ObjectField,
    )

    private sealed interface EdgeReason {
        fun describe(): String

        data class ResolverInput(
            val resolver: Schema.ObjectField,
        ) : EdgeReason {
            override fun describe(): String =
                "resolver ${resolver.coordinate()} requires the branch"
        }

        data class VariableProduction(
            val variable: Arguments.Variable,
            val providerPath: String,
            val productionPath: String,
            val usePath: String,
        ) : EdgeReason {
            override fun describe(): String =
                "variable \$${variable.variableName} defined by " +
                    "${variable.field.coordinate()} has provider path $providerPath " +
                    "with production path $productionPath " +
                    "and use path $usePath"
        }
    }

    private val graphs =
        fieldResolvers.keys
            .map { field -> field.containingDef as Schema.Object }
            .distinct()
            .associateWith { BranchGraph(it) }

    fun validate() {
        addResolverInputEdges()
        closeVariableProductionEdges()
        graphs.values.forEach(BranchGraph::requireAcyclic)
    }

    private fun addResolverInputEdges() {
        fieldResolvers.forEach { (outputField, resolver) ->
            val consumer = outputField as Schema.ObjectField
            val graph = graphs.getValue(consumer.containingDef)
            resolver.objectFragment.forEach { selection ->
                selection.branchOn(consumer.containingDef)?.let { prerequisite ->
                    graph.add(
                        Edge(prerequisite, consumer),
                        EdgeReason.ResolverInput(consumer),
                    )
                }
            }
        }
    }

    private fun closeVariableProductionEdges() {
        var changed: Boolean
        do {
            val additions = mutableListOf<Pair<Edge, EdgeReason.VariableProduction>>()
            fieldResolvers.values.forEach { resolver ->
                resolver.variables.forEach variables@{ (variable, definition) ->
                    if (definition !is VariableDefinition.FromObjectField) return@variables
                    val providerPath = definition.path
                    val type = variable.field.containingDef
                    val graph = graphs.getValue(type)
                    val providerBranch =
                        type.requireField(providerPath.first().field.name)
                    val productionPaths = graph.prerequisitePathsTo(providerBranch)
                    val renderedProviderPath =
                        providerPath.joinToString("/") { key -> key.field.name }
                    val uses =
                        resolver.objectFragment.variableUsePaths(variable, type)

                    productionPaths.forEach { (production, productionPath) ->
                        uses.forEach { (useBranch, usePaths) ->
                            usePaths.forEach { usePath ->
                                additions +=
                                    Edge(production, useBranch) to
                                        EdgeReason.VariableProduction(
                                            variable = variable,
                                            providerPath = renderedProviderPath,
                                            productionPath =
                                                productionPath.joinToString(" -> ") { it.name },
                                            usePath = usePath,
                                        )
                            }
                        }
                    }
                }
            }
            changed =
                additions.fold(false) { result, (edge, reason) ->
                    graphs.getValue(edge.consumer.containingDef).add(edge, reason) || result
                }
            if (changed) graphs.values.forEach(BranchGraph::requireAcyclic)
        } while (changed)
    }

    private class BranchGraph(
        private val type: Schema.Object,
    ) {
        private val reasons = linkedMapOf<Edge, MutableSet<EdgeReason>>()

        fun add(
            edge: Edge,
            reason: EdgeReason,
        ): Boolean {
            require(
                edge.prerequisite.containingDef == type &&
                    edge.consumer.containingDef == type,
            )
            val isNew = edge !in reasons
            reasons.getOrPut(edge, ::linkedSetOf).add(reason)
            return isNew
        }

        fun prerequisitePathsTo(
            branch: Schema.ObjectField,
        ): Map<Schema.ObjectField, List<Schema.ObjectField>> {
            val result = linkedMapOf(branch to listOf(branch))
            val pending = ArrayDeque<Schema.ObjectField>()
            pending += branch
            while (pending.isNotEmpty()) {
                val consumer = pending.removeFirst()
                reasons.keys
                    .filter { edge -> edge.consumer == consumer }
                    .map(Edge::prerequisite)
                    .filter { prerequisite -> prerequisite !in result }
                    .forEach { prerequisite ->
                        result[prerequisite] =
                            listOf(prerequisite) + result.getValue(consumer)
                        pending += prerequisite
                    }
            }
            return result
        }

        fun requireAcyclic() {
            val cycle = findCycle() ?: return
            val cycleEdges = cycle.zipWithNext().map { (from, to) -> Edge(from, to) }
            val renderedCycle =
                cycle.joinToString(" -> ") { branch -> branch.name }
            val renderedReasons =
                cycleEdges.joinToString("; ") { edge ->
                    "${edge.prerequisite.name} -> ${edge.consumer.name}: " +
                        reasons.getValue(edge).joinToString(" and ") { it.describe() }
                }
            throw IllegalArgumentException(
                "Depth-first variable branch order on ${type.name} contains a cycle " +
                    "$renderedCycle. Edges: $renderedReasons",
            )
        }

        private fun findCycle(): List<Schema.ObjectField>? {
            val visited = linkedSetOf<Schema.ObjectField>()
            val active = linkedSetOf<Schema.ObjectField>()
            val path = mutableListOf<Schema.ObjectField>()
            val vertices =
                reasons.keys
                    .flatMap { edge -> listOf(edge.prerequisite, edge.consumer) }
                    .distinct()
                    .sortedBy(Schema.ObjectField::name)

            fun visit(vertex: Schema.ObjectField): List<Schema.ObjectField>? {
                if (vertex in active) {
                    val start = path.indexOf(vertex)
                    return path.subList(start, path.size).toList() + vertex
                }
                if (!visited.add(vertex)) return null

                active += vertex
                path += vertex
                val cycle =
                    reasons.keys
                        .filter { edge -> edge.prerequisite == vertex }
                        .map(Edge::consumer)
                        .distinct()
                        .sortedBy(Schema.ObjectField::name)
                        .firstNotNullOfOrNull(::visit)
                path.removeAt(path.lastIndex)
                active -= vertex
                return cycle
            }

            return vertices.firstNotNullOfOrNull(::visit)
        }
    }
}

private fun Selection.branchOn(type: Schema.Object): Schema.ObjectField? =
    if (type in possibleTypes) {
        type.requireField(key.field.name)
    } else {
        null
    }

private fun SelectionForest.variableUsePaths(
    variable: Arguments.Variable,
    type: Schema.Object,
): Map<Schema.ObjectField, Set<String>> {
    val result = linkedMapOf<Schema.ObjectField, MutableSet<String>>()
    forEach { selection ->
        val branch = selection.branchOn(type) ?: return@forEach
        selection.pathsContaining(variable).forEach { path ->
            result.getOrPut(branch, ::linkedSetOf).add(path)
        }
    }
    return result
}

private fun Selection.pathsContaining(
    variable: Arguments.Variable,
    prefix: List<String> = emptyList(),
): Set<String> {
    val path = prefix + key.field.name
    val result = linkedSetOf<String>()
    if (variable in key.arguments.variables()) {
        result += path.joinToString("/")
    }
    subselections.forEach { selection ->
        result += selection.pathsContaining(variable, path)
    }
    return result
}

private fun Schema.ObjectField.coordinate(): String =
    "${containingDef.name}/$name"
