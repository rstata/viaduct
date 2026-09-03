package semantics.resolvers

import model.Arguments
import model.EngineInputData
import model.ObjectEngineResult
import model.Selection
import model.SelectionForest
import model.flatMapToSelectionForest
import model.objectKey
import model.requireField
import model.selectionForestOf
import model.substituteTemplates
import model.registry.FieldResolver
import model.registry.VariableDefinition
import semantics.shared.instantiateBindings
import semantics.shared.OperationContext

/** Extends this demand with every encountered successor resolver's transitive input demand. */
context(operation: OperationContext)
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
                if (arguments !is Arguments.Resolved || key.field !in operation.resolverRegistry) {
                    selectionForestOf()
                } else {
                    operation.resolverRegistry
                        .resolver(key.field)
                        .objectFragmentWithFromArguments(arguments)
                        .successorDemand()
                }
            }
        selectionForestOf(rootedSelection) + resolverInputDemand
    }

/** Extends this demand with the paths needed to find every successor resolver boundary. */
context(operation: OperationContext)
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

context(operation: OperationContext)
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
        if (arguments !is Arguments.Resolved || key.field !in operation.resolverRegistry) {
            selectionForestOf()
        } else {
            operation.resolverRegistry
                .resolver(key.field)
                .objectFragmentWithFromArguments(arguments)
                .boundarySkeleton()
                .successorBoundaryDemand()
        }
    }

context(operation: OperationContext)
private fun SelectionForest.boundarySkeleton(): SelectionForest =
    flatMap { selection ->
        val nested = selection.subselections.boundarySkeleton()
        val isResolverBoundary =
            selection.possibleTypes.any { possibleType ->
                val field = possibleType.requireField(selection.key.field.name)
                field in operation.resolverRegistry
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
                variable to definition.read(arguments)
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
