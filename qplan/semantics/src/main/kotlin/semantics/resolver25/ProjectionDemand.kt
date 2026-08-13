package semantics.resolver25

import model.Assumptions
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.concatenateSelectionForests
import model.flatMapToSelectionForest
import model.objectKey
import model.selectionForestOf
import model.toSelectionForest
import model.usedVariables

// Defers an ungrounded resolver boundary while retaining the fixed predecessor demand that its
// eventual exact occurrence will need. This demand must reach a selective containing producer
// before that producer launches because late deepening cannot recover omitted passive values.
context(world: Assumptions)
internal fun SelectionForest.projectionDemandDeferringTemplates(): SelectionForest {
    val passiveDemandByResolverField = mutableMapOf<Schema.ObjectField, SelectionForest>()
    val groundedDemandByResolverField = mutableMapOf<Schema.ObjectField, SelectionForest>()
    return projectionDemandDeferringTemplates(
        passiveDemandByResolverField,
        groundedDemandByResolverField,
    )
}

context(world: Assumptions)
private fun SelectionForest.projectionDemandDeferringTemplates(
    passiveDemandByResolverField: MutableMap<Schema.ObjectField, SelectionForest>,
    groundedDemandByResolverField: MutableMap<Schema.ObjectField, SelectionForest>,
): SelectionForest =
    coalesceEquivalentSelections()
        .flatMap { selection ->
            if (
                selection.key.arguments.usedVariables().any { variable ->
                    variable is Value.Variable.Template
                }
            ) {
                selection.possibleTypes.flatMapToSelectionForest { possibleType ->
                    val field = selection.objectKey(possibleType).field
                    check(field in world.resolverRegistry) {
                        "Template-bearing passive field is unsupported: $field"
                    }
                    field.fixedPassivePredecessorDemand(passiveDemandByResolverField)
                }
            } else {
                val groundedResolverDemand =
                    selection.possibleTypes.flatMapToSelectionForest { possibleType ->
                        val field = selection.objectKey(possibleType).field
                        if (field in world.resolverRegistry) {
                            field.fixedGroundedProjectionDemand(
                                passiveDemandByResolverField,
                                groundedDemandByResolverField,
                            )
                        } else {
                            selectionForestOf()
                        }
                    }
                selectionForestOf(
                    Selection.of(
                        key = selection.key,
                        possibleTypes = selection.possibleTypes,
                        subselections =
                            selection.subselections.projectionDemandDeferringTemplates(
                                passiveDemandByResolverField,
                                groundedDemandByResolverField,
                            ),
                    ),
                ) + groundedResolverDemand
            }
        }.coalesceEquivalentSelections()

context(world: Assumptions)
private fun Schema.ObjectField.fixedGroundedProjectionDemand(
    passiveDemandByResolverField: MutableMap<Schema.ObjectField, SelectionForest>,
    groundedDemandByResolverField: MutableMap<Schema.ObjectField, SelectionForest>,
): SelectionForest =
    groundedDemandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .projectionDemandDeferringTemplates(
                passiveDemandByResolverField,
                groundedDemandByResolverField,
            ).also { demand -> groundedDemandByResolverField[this] = demand }

context(world: Assumptions)
private fun Schema.ObjectField.fixedPassivePredecessorDemand(
    passiveDemandByResolverField: MutableMap<Schema.ObjectField, SelectionForest>,
): SelectionForest =
    passiveDemandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .passivePredecessorDemand(passiveDemandByResolverField)
            .also { demand -> passiveDemandByResolverField[this] = demand }

context(world: Assumptions)
private fun SelectionForest.passivePredecessorDemand(
    passiveDemandByResolverField: MutableMap<Schema.ObjectField, SelectionForest>,
): SelectionForest =
    coalesceEquivalentSelections()
        .flatMap { selection ->
            selection.possibleTypes.flatMapToSelectionForest { possibleType ->
                val key = selection.objectKey(possibleType)
                if (key.field in world.resolverRegistry) {
                    key.field.fixedPassivePredecessorDemand(passiveDemandByResolverField)
                } else {
                    selectionForestOf(
                        Selection.of(
                            key = key,
                            possibleTypes = setOf(possibleType),
                            subselections =
                                selection.subselections.passivePredecessorDemand(
                                    passiveDemandByResolverField,
                                ),
                        ),
                    )
                }
            }
        }.coalesceEquivalentSelections()

private data class ProjectionSelectionIdentity(
    val key: Value.Key,
    val possibleTypes: Set<Schema.ObjectType>,
)

private fun SelectionForest.coalesceEquivalentSelections(): SelectionForest {
    val childrenBySelection =
        linkedMapOf<ProjectionSelectionIdentity, MutableList<SelectionForest>>()
    forEach { selection ->
        childrenBySelection
            .getOrPut(
                ProjectionSelectionIdentity(selection.key, selection.possibleTypes),
                ::mutableListOf,
            ).add(selection.subselections)
    }
    return childrenBySelection
        .map { (identity, children) ->
            Selection.of(
                key = identity.key,
                possibleTypes = identity.possibleTypes,
                subselections = children.concatenateSelectionForests(),
            )
        }.toSelectionForest()
}
