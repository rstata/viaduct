package semantics.resolver26

import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.ObjectSelection
import model.PathComponent
import model.ResolverOccurrenceId
import model.ResolverOutputData
import model.RootFieldReferenceData
import model.SelectionForest
import model.outputType
import model.registry.FieldResolver
import model.registry.ResolverFragments
import model.registry.VariableInstanceDefinition
import viaduct.graphql.schema.ViaductSchema

/** One installed field-resolution task's source occurrence. */
internal sealed interface ResolverOccurrenceContext {
    val selection: ObjectSelection

    val publicationPath: List<PathComponent>

    val publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>
        get() = selection.key.field.outputType

    val publicationConstructionDemand: SelectionForest
        get() = selection.subselections
}

/** Stable closure output needed to invoke one resolver occurrence. */
internal data class FieldResolverOccurrenceContext(
    override val selection: ObjectSelection,
    val invocationRoot: ObjectEngineResult,
    val invocationPath: List<PathComponent>,
    val resolverOccurrenceId: ResolverOccurrenceId,
    val resolver: FieldResolver,
    val inputMaterializeSelections: MaterializeSelectionForest,
    val variableDefinitions: List<VariableInstanceDefinition>,
    val fragments: ResolverFragments,
) : ResolverOccurrenceContext {
    override val publicationPath: List<PathComponent>
        get() = invocationPath
}

/** A source-provided symbolic reference waiting to be converted into a resolver occurrence. */
internal data class RootFieldReferenceOccurrence(
    override val selection: ObjectSelection,
    val reference: RootFieldReferenceData,
    override val publicationPath: List<PathComponent>,
    override val publicationExpectedType: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef> =
        selection.key.field.outputType,
) : ResolverOccurrenceContext

/** A conditioned passive value whose embedded references must not launch before activation. */
internal data class PassiveValueOccurrence(
    override val selection: ObjectSelection,
    val value: ResolverOutputData?,
    val invocationDemand: SelectionForest,
    override val publicationConstructionDemand: SelectionForest,
    override val publicationPath: List<PathComponent>,
) : ResolverOccurrenceContext
