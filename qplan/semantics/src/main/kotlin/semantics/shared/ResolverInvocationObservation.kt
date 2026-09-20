package semantics.shared

import model.Arguments
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.SelectionForest
import model.usedVariables
import model.variableArgumentNames
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/**
 * Facts captured immediately before one ordinary or reference-target resolver invocation.
 * Records attempts, including invocations that subsequently throw or are cancelled; no output is
 * recorded. A null [suppliedDemand] denotes complete, nonselective execution.
 */
data class ResolverInvocationObservation(
    val occurrencePath: List<PathComponent>,
    val field: ViaductSchema.ObjectField,
    val input: EngineObjectData.Sync,
    val inputSelections: MaterializeSelectionForest,
    val arguments: Arguments.Resolved,
    val suppliedDemand: SelectionForest?,
    val resolverOccurrenceId: ResolverOccurrenceId,
) {
    /**
     * Variable-bearing arguments retained in the recorded key. Resolver26 preserves symbolic
     * keys here; families that record grounded keys report zero.
     */
    val variableArgumentCount: Int
        get() = (occurrencePath.last() as ObjectEngineResult.ObjectKey).arguments.variableArgumentNames().size

    /** Owning occurrences of variables used by the invocation's original argument expressions. */
    val variableResolverOccurrenceIds: Set<ResolverOccurrenceId>
        get() = (occurrencePath.last() as ObjectEngineResult.ObjectKey).arguments.usedVariables()
            .mapNotNullTo(linkedSetOf()) { it.instanceId?.resolverOccurrenceId }
}
