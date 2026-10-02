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
import semantics.shared.liftParentSuccessorDemand
import semantics.shared.instantiateBindings
import semantics.shared.SharedOperationContext

/** Extends this demand with every encountered successor resolver's transitive input demand. */
fun SelectionForest.successorDemand(operation: SharedOperationContext<*>): SelectionForest {
    val demand = successorDemandWithoutParentLifting(operation)
    return demand + demand.liftParentSuccessorDemand(operation.world)
}

private fun SelectionForest.successorDemandWithoutParentLifting(operation: SharedOperationContext<*>): SelectionForest =
    flatMap { selection ->
        val nestedDemand = selection.subselections.successorDemand(operation)
        val rootedSelection =
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = nestedDemand,
                inclusionCondition = selection.inclusionCondition,
            )
        val resolverInputDemand =
            selection.possibleTypes.flatMapToSelectionForest { possibleType ->
                val specializedKey = selection.objectKey(possibleType)
                val key =
                    ObjectEngineResult.GroundKey.of(
                        field = specializedKey.field,
                        arguments =
                            specializedKey.arguments.instantiateBindings(
                                operation,
                                specializedKey.field,
                            ),
                    )
                val arguments = key.arguments
                if (arguments !is Arguments.Resolved || key.field !in operation.world.resolverRegistry) {
                    selectionForestOf()
                } else {
                    operation.world.resolverRegistry
                        .resolver(key.field)
                        .objectFragmentWithFromArguments(arguments)
                        .successorDemand(operation)
                }
            }
        selectionForestOf(rootedSelection) + resolverInputDemand
    }

/** Extends this demand with the paths needed to find every successor resolver boundary. */
fun SelectionForest.successorBoundaryDemand(operation: SharedOperationContext<*>): SelectionForest {
    val demand = successorBoundaryDemandWithoutParentLifting(operation)
    return demand + demand.liftParentSuccessorDemand(operation.world)
}

private fun SelectionForest.successorBoundaryDemandWithoutParentLifting(operation: SharedOperationContext<*>): SelectionForest =
    flatMap { selection ->
        val requested =
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.successorBoundaryDemand(operation),
                inclusionCondition = selection.inclusionCondition,
            )

        selectionForestOf(requested) + selection.successorInputBoundaries(operation)
    }

private fun Selection.successorInputBoundaries(operation: SharedOperationContext<*>): SelectionForest =
    possibleTypes.flatMapToSelectionForest { possibleType ->
        val specializedKey = objectKey(possibleType)
        val key =
            ObjectEngineResult.GroundKey.of(
                field = specializedKey.field,
                arguments =
                    specializedKey.arguments.instantiateBindings(
                        operation,
                        specializedKey.field,
                    ),
            )
        val arguments = key.arguments
        if (arguments !is Arguments.Resolved || key.field !in operation.world.resolverRegistry) {
            selectionForestOf()
        } else {
            operation.world.resolverRegistry
                .resolver(key.field)
                .objectFragmentWithFromArguments(arguments)
                .boundarySkeleton(operation)
                .successorBoundaryDemand(operation)
        }
    }

private fun SelectionForest.boundarySkeleton(operation: SharedOperationContext<*>): SelectionForest =
    flatMap { selection ->
        val nested = selection.subselections.boundarySkeleton(operation)
        val isResolverBoundary =
            selection.possibleTypes.any { possibleType ->
                val field = possibleType.requireField(selection.key.field.name)
                field in operation.world.resolverRegistry
            }

        if (isResolverBoundary || !nested.isEmpty()) {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    subselections = nested,
                    inclusionCondition = selection.inclusionCondition,
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
                inclusionCondition = selection.inclusionCondition,
                subselections = selection.subselections.substitute(bindings),
            ),
        )
    }
