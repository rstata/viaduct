package semantics.resolver26

import model.MaterializeSelectionForest
import model.ObjectSelection
import model.ResolverOccurrenceId
import model.registry.FieldResolver
import model.registry.ResolverFragments
import model.registry.VariableInstanceDefinition

/** Stable closure output needed to execute one Resolver26 field-resolver occurrence. */
internal data class FieldResolverOccurrenceContext(
    val selection: ObjectSelection,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolver: FieldResolver,
    val inputMaterializeSelections: MaterializeSelectionForest,
    val variableDefinitions: List<VariableInstanceDefinition>,
    val fragments: ResolverFragments,
)
