package model.registry

import model.Assumptions
import model.Selection
import model.SelectionForest
import model.Value
import model.instantiateBindings
import model.objectKey
import model.selectionForestOf
import model.substituteTemplates
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
            selection.possibleTypes.fold(selectionForestOf()) { demand, possibleType ->
                val specializedKey = selection.objectKey(possibleType)
                val key =
                    Value.GroundKey.of(
                        field = specializedKey.field,
                        arguments = specializedKey.arguments.instantiateBindings(),
                    )
                if (
                    key.arguments.containsErrorValue() ||
                    key.field !in world.resolverRegistry
                ) {
                    demand
                } else {
                    demand +
                        world.resolverRegistry
                            .resolver(key.field)
                            .objectFragmentWithFromArguments(key.arguments)
                            .successorDemand()
                }
            }
        selectionForestOf(rootedSelection) + resolverInputDemand
    }

/**
 * Extends this output demand with the paths needed to find every successor behavioral boundary.
 *
 * Unlike [successorDemand], this operation assumes that passive traversal copies the complete
 * finite resolver output. It therefore omits passive input leaves while retaining resolver and
 * `__typename` selections, their passive ancestor paths, and transitive successor boundaries.
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

/**
 * Extends output demand through every statically ground successor boundary.
 *
 * Variable-bearing key branches are left for their exact runtime occurrences to stamp and expand.
 * Variable-free provider branches remain visible so complete-output traversal can create their
 * nested resolver orchestrators.
 */
context(world: Assumptions)
fun SelectionForest.successorGroundBoundaryDemand(): SelectionForest =
    flatMap { selection ->
        val requested =
            if (selection.key.arguments.usedVariables().isEmpty()) {
                selectionForestOf(
                    Selection.of(
                        key = selection.key,
                        possibleTypes = selection.possibleTypes,
                        subselections =
                            selection.subselections
                                .successorGroundBoundaryDemand(),
                    ),
                )
            } else {
                selectionForestOf()
            }

        requested +
            selection.successorGroundInputBoundaries()
    }

context(world: Assumptions)
private fun Selection.successorInputBoundaries(): SelectionForest =
    possibleTypes.fold(selectionForestOf()) { demand, possibleType ->
        val specializedKey = objectKey(possibleType)
        val key =
            Value.GroundKey.of(
                field = specializedKey.field,
                arguments = specializedKey.arguments.instantiateBindings(),
            )
        if (
            key.arguments.containsErrorValue() ||
            key.field !in world.resolverRegistry
        ) {
            demand
        } else {
            demand +
                world.resolverRegistry
                    .resolver(key.field)
                    .objectFragmentWithFromArguments(key.arguments)
                    .boundarySkeleton()
                    .successorBoundaryDemand()
        }
    }

context(world: Assumptions)
private fun Selection.successorGroundInputBoundaries(): SelectionForest =
    possibleTypes.fold(selectionForestOf()) { demand, possibleType ->
        val specializedKey = objectKey(possibleType)
        if (specializedKey.field !in world.resolverRegistry) {
            return@fold demand
        }
        val resolver = world.resolverRegistry.resolver(specializedKey.field)
        val openArguments = specializedKey.arguments
        val hasVariables = openArguments.usedVariables().isNotEmpty()
        val arguments =
            if (hasVariables) {
                null
            } else {
                openArguments.instantiateBindings()
            }
        if (arguments?.containsErrorValue() == true) {
            demand
        } else {
            val objectFragment =
                if (arguments == null) {
                    resolver.objectFragment
                } else {
                    resolver.objectFragmentWithFromArguments(arguments)
                }
            demand +
                objectFragment
                    .successorGroundBoundaryDemand()
        }
    }

context(world: Assumptions)
private fun SelectionForest.boundarySkeleton(): SelectionForest =
    flatMap { selection ->
        val nested = selection.subselections.boundarySkeleton()
        val isBehavioral =
            selection.possibleTypes.any { possibleType ->
                val field = possibleType.fields.getValue(selection.key.field.fieldName)
                world.behavioral(field)
            }

        if (isBehavioral || !nested.isEmpty()) {
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
    arguments: Value.Arguments,
): SelectionForest {
    val bindings =
        variables.mapNotNull { (variable, definition) ->
            (definition as? VariableDefinition.FromArgument)?.let {
                variable to
                    arguments.fieldValues.getValue(
                        definition.argument.argumentName,
                    )
            }
        }.toMap()
    return objectFragment.substitute(bindings)
}

private fun SelectionForest.substitute(
    bindings: Map<Value.Variable.Template, Value.Input?>,
): SelectionForest =
    flatMap { selection ->
        selectionForestOf(
            Selection.of(
                key =
                    Value.Key.of(
                        field = selection.key.field,
                        arguments = selection.key.arguments.substituteTemplates(bindings),
                    ),
                possibleTypes = selection.possibleTypes,
                subselections = selection.subselections.substitute(bindings),
            ),
        )
    }

private fun Value.Arguments.containsErrorValue(): Boolean =
    fieldValues.values.any { value -> value.containsErrorValue() }

private fun Value.Input?.containsErrorValue(): Boolean =
    when (this) {
        Value.Error -> true
        is Value.InputList -> values.any { value -> value.containsErrorValue() }
        is Value.InputObject ->
            fieldValues.values.any { value -> value.containsErrorValue() }
        else -> false
    }
