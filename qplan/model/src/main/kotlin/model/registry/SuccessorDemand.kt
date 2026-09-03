package model.registry

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult
import model.Assumptions
import model.EngineInputData
import model.Arguments
import model.Selection
import model.SelectionForest
import model.concatenateSelectionForests
import model.flatMapToSelectionForest
import model.instantiateBindings
import model.objectKey
import model.requireField
import model.selectionForestOf
import model.substituteTemplates

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
                    definition.read(arguments)
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
