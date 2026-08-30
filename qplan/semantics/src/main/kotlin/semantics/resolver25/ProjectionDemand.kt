package semantics.resolver25

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult

import model.Assumptions
import model.Selection
import model.SelectionForest
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
    val passiveDemandByResolverField = mutableMapOf<ViaductSchema.ObjectField, SelectionForest>()
    val groundedDemandByResolverField = mutableMapOf<ViaductSchema.ObjectField, SelectionForest>()
    return projectionDemandDeferringTemplates(
        passiveDemandByResolverField,
        groundedDemandByResolverField,
    )
}

// Retains open resolver boundaries only as conservative potential demand. Unlike projection
// demand, this forest is never supplied to an ancestor resolver, so argument-bearing boundaries
// can remain present while their fixed input demand is expanded.
context(world: Assumptions)
internal fun SelectionForest.potentialSuccessorDemand(): SelectionForest =
    potentialSuccessorDemand(mutableMapOf())

context(world: Assumptions)
private fun SelectionForest.potentialSuccessorDemand(
    demandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
): SelectionForest =
    coalesceEquivalentSelections()
        .flatMap { selection ->
            val requested =
                selectionForestOf(
                    Selection.of(
                        key = selection.key,
                        possibleTypes = selection.possibleTypes,
                        subselections =
                            selection.subselections.potentialSuccessorDemand(
                                demandByResolverField,
                            ),
                    ),
                )
            val resolverInputDemand =
                selection.possibleTypes.flatMapToSelectionForest { possibleType ->
                    val field = selection.objectKey(possibleType).field
                    if (field in world.resolverRegistry) {
                        field.fixedPotentialSuccessorDemand(demandByResolverField)
                    } else {
                        selectionForestOf()
                    }
                }
            requested + resolverInputDemand
        }.coalesceEquivalentSelections()

context(world: Assumptions)
private fun ViaductSchema.ObjectField.fixedPotentialSuccessorDemand(
    demandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
): SelectionForest =
    demandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .potentialSuccessorDemand(demandByResolverField)
            .also { demand -> demandByResolverField[this] = demand }

context(world: Assumptions)
private fun SelectionForest.projectionDemandDeferringTemplates(
    passiveDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
    groundedDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
): SelectionForest =
    coalesceEquivalentSelections()
        .flatMap { selection ->
            if (
                selection.key.arguments.usedVariables().any { variable ->
                    variable.isTemplate
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
private fun ViaductSchema.ObjectField.fixedGroundedProjectionDemand(
    passiveDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
    groundedDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
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
private fun ViaductSchema.ObjectField.fixedPassivePredecessorDemand(
    passiveDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
): SelectionForest =
    passiveDemandByResolverField[this]
        ?: world.resolverRegistry
            .resolver(this)
            .objectFragment
            .passivePredecessorDemand(passiveDemandByResolverField)
            .also { demand -> passiveDemandByResolverField[this] = demand }

context(world: Assumptions)
private fun SelectionForest.passivePredecessorDemand(
    passiveDemandByResolverField: MutableMap<ViaductSchema.ObjectField, SelectionForest>,
): SelectionForest =
    coalesceEquivalentSelections()
        .flatMap { selection ->
            selection.possibleTypes.flatMapToSelectionForest { possibleType ->
                val key = selection.objectKey(possibleType)
                if (key.field in world.resolverRegistry) {
                    val potentiallyPassiveSelection =
                        if (key.field.args.isEmpty()) {
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
                        } else {
                            selectionForestOf()
                        }
                    potentiallyPassiveSelection +
                        key.field.fixedPassivePredecessorDemand(
                            passiveDemandByResolverField,
                        )
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
    val key: ObjectEngineResult.Key,
    val possibleTypes: Set<ViaductSchema.Object>,
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
