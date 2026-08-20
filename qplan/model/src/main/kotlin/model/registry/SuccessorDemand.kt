package model.registry

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult
import model.Assumptions
import model.EngineInputData
import model.Arguments
import model.Selection
import model.SelectionForest
import model.Stamp
import model.concatenateSelectionForests
import model.fetchBindings
import model.flatMapToSelectionForest
import model.instantiateBindings
import model.objectKey
import model.requireField
import model.selectionForestOf
import model.substituteTemplates
import model.toSelectionForest
import model.usedVariables

/**
 * Extends this output demand with every encountered successor resolver's transitive input demand.
 *
 * Each predecessor demand is rooted at its successor occurrence's containing object. Recursing
 * through subselections preserves the selection-occurrence nesting path and concrete-type guards.
 */
context(world: Assumptions)
fun SelectionForest.successorDemand(): SelectionForest =
    flatMap { selection ->
        val nestedDemand = selection.subselections.successorDemand()
        val rootedSelection =
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = nestedDemand,
            )
        val resolverInputDemand =
            selection.possibleTypes.flatMapToSelectionForest { possibleType ->
                val specializedKey = selection.objectKey(possibleType)
                val key =
                    ObjectEngineResult.GroundKey.of(
                        field = specializedKey.field,
                        arguments =
                            specializedKey.arguments.instantiateBindings(
                                specializedKey.field,
                            ),
                    )
                val arguments = key.arguments
                if (arguments !is Arguments.Resolved || key.field !in world.resolverRegistry) {
                    selectionForestOf()
                } else {
                    world.resolverRegistry
                        .resolver(key.field)
                        .objectFragmentWithFromArguments(arguments)
                        .successorDemand()
                }
            }
        selectionForestOf(rootedSelection) + resolverInputDemand
    }

/**
 * Extends output demand after awaiting stamped bindings, while deferring unstamped templates.
 *
 * A template-bearing branch belongs to a resolver occurrence whose exact path is not yet available.
 * Stamped branches retain ordinary full successor closure after their bindings complete. Branches
 * whose fetched keys are equal coalesce to that ground key. Provider-path markers remain attached
 * to their grounded keys.
 */
context(world: Assumptions)
suspend fun SelectionForest.fetchSuccessorDemandDeferringTemplates(): SelectionForest {
    val childrenBySelection =
        linkedMapOf<SelectionIdentity, MutableList<SelectionForest>>()
    val pendingSelections = mutableListOf<SelectionIdentity>()

    fun addDemand(demand: SelectionForest) {
        demand.forEach { selection ->
            val identity =
                SelectionIdentity(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                )
            val children = childrenBySelection[identity]
            if (children == null) {
                childrenBySelection[identity] = mutableListOf(selection.subselections)
                pendingSelections += identity
            } else {
                children += selection.subselections
            }
        }
    }

    addDemand(this)
    val deferredSelections = mutableSetOf<SelectionIdentity>()
    val expandedTemplateFields = mutableSetOf<ViaductSchema.ObjectField>()
    val expandedGroundedKeys = mutableSetOf<ObjectEngineResult.GroundKey>()
    var pendingIndex = 0
    while (pendingIndex < pendingSelections.size) {
        val identity = pendingSelections[pendingIndex++]
        val selection =
            Selection.of(
                key = identity.key,
                possibleTypes = identity.possibleTypes,
                subselections = selectionForestOf(),
            )
        if (
            selection.key.arguments.usedVariables().any { variable ->
                variable.isTemplate
            }
        ) {
            deferredSelections += identity
            selection.possibleTypes
                .forEach { possibleType ->
                    val field = selection.objectKey(possibleType).field
                    if (
                        field in world.resolverRegistry &&
                        expandedTemplateFields.add(field)
                    ) {
                        addDemand(world.resolverRegistry.resolver(field).objectFragment)
                    }
                }
            continue
        }

        for (possibleType in selection.possibleTypes) {
            val specializedKey = selection.objectKey(possibleType)
            val key =
                ObjectEngineResult.GroundKey.of(
                    field = specializedKey.field,
                    arguments =
                        specializedKey.arguments.fetchBindings(
                            specializedKey.field,
                        ),
                )
            val arguments = key.arguments
            if (
                arguments is Arguments.Resolved &&
                key.field in world.resolverRegistry &&
                expandedGroundedKeys.add(key)
            ) {
                addDemand(
                    world.resolverRegistry
                        .resolver(key.field)
                        .objectFragmentWithFromArguments(arguments),
                )
            }
        }
    }

    val groundedChildrenBySelection =
        linkedMapOf<SelectionIdentity, MutableList<SelectionForest>>()
    childrenBySelection.forEach { (identity, children) ->
        if (identity !in deferredSelections) {
            val groundedIdentity =
                SelectionIdentity(
                    key = identity.key.fetchStampedBindings(),
                    possibleTypes = identity.possibleTypes,
                )
            groundedChildrenBySelection
                .getOrPut(groundedIdentity, ::mutableListOf)
                .add(children.concatenateSelectionForests())
        }
    }
    return groundedChildrenBySelection
        .map { (identity, children) ->
            Selection.of(
                key = identity.key,
                possibleTypes = identity.possibleTypes,
                subselections =
                    children
                        .concatenateSelectionForests()
                        .fetchSuccessorDemandDeferringTemplates(),
            )
        }.toSelectionForest()
}

// These occurrences would converge at every concrete merge boundary. Coalescing them while closing
// demand avoids expanding the same resolver-demand DAG once per incoming path.
private data class SelectionIdentity(
    val key: ObjectEngineResult.Key,
    val possibleTypes: Set<ViaductSchema.Object>,
)

context(world: Assumptions)
private suspend fun ObjectEngineResult.Key.fetchStampedBindings(): ObjectEngineResult.Key {
    if (arguments is Arguments.Resolved) return this
    val groundedArguments = arguments.fetchBindings(field)
    val currentSelectionStamp = stamp as? Stamp.Occurrence
    val groundedKey =
        if (currentSelectionStamp != null) {
            ObjectEngineResult.Key.of(
                stamp = currentSelectionStamp,
                field = field,
                arguments = groundedArguments,
            )
        } else {
            ObjectEngineResult.Key.of(
                field = field,
                arguments = groundedArguments,
            )
        }
    val marker =
        (this as? ObjectEngineResult.VariableKey)?.variableDefinedByThisKey
    return if (marker == null) {
        groundedKey
    } else {
        ObjectEngineResult.VariableKey.of(
            key = groundedKey,
            variableDefinedByThisKey = marker,
        )
    }
}

/**
 * Extends this output demand with the paths needed to find every successor resolver boundary.
 *
 * Unlike [successorDemand], this operation assumes that passive traversal copies the complete
 * finite resolver output. It therefore omits passive input leaves while retaining resolver
 * selections, their passive ancestor paths, and transitive successor boundaries.
 */
context(world: Assumptions)
fun SelectionForest.successorBoundaryDemand(): SelectionForest =
    flatMap { selection ->
        val requested =
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.successorBoundaryDemand(),
            )

        selectionForestOf(requested) + selection.successorInputBoundaries()
    }

context(world: Assumptions)
private fun Selection.successorInputBoundaries(): SelectionForest =
    possibleTypes.flatMapToSelectionForest { possibleType ->
        val specializedKey = objectKey(possibleType)
        val key =
            ObjectEngineResult.GroundKey.of(
                field = specializedKey.field,
                arguments =
                    specializedKey.arguments.instantiateBindings(
                        specializedKey.field,
                    ),
            )
        val arguments = key.arguments
        if (arguments !is Arguments.Resolved || key.field !in world.resolverRegistry) {
            selectionForestOf()
        } else {
            world.resolverRegistry
                .resolver(key.field)
                .objectFragmentWithFromArguments(arguments)
                .boundarySkeleton()
                .successorBoundaryDemand()
        }
    }

context(world: Assumptions)
private fun SelectionForest.boundarySkeleton(): SelectionForest =
    flatMap { selection ->
        val nested = selection.subselections.boundarySkeleton()
        val isResolverBoundary =
            selection.possibleTypes.any { possibleType ->
                val field = possibleType.requireField(selection.key.field.name)
                field in world.resolverRegistry
            }

        if (isResolverBoundary || !nested.isEmpty()) {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = nested,
                ),
            )
        } else {
            selectionForestOf()
        }
    }

private fun FieldResolver.objectFragmentWithFromArguments(
    arguments: Arguments.Resolved,
): SelectionForest {
    val bindings =
        variables.mapNotNull { (variable, definition) ->
            (definition as? VariableDefinition.FromArgument)?.let {
                variable to
                    arguments.fieldValues.getValue(
                        definition.argument.name,
                    )
            }
        }.toMap()
    return objectFragment.substitute(bindings)
}

private fun SelectionForest.substitute(
    bindings: Map<Arguments.Variable, EngineInputData?>,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    ObjectEngineResult.Key.of(
                        field = selection.key.field,
                        arguments =
                            selection.key.arguments.substituteTemplates(
                                selection.key.field,
                                bindings,
                            ),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.substitute(bindings),
            ),
        )
    }
