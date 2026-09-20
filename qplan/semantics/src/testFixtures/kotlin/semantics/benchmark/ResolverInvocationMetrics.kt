package semantics.benchmark

import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.usedVariables
import model.variableArgumentNames
import semantics.shared.ResolverInvocationObservation

/**
 * Variable-bearing arguments retained in the recorded key. Resolver26 preserves symbolic
 * keys here; families that record grounded keys report zero.
 */
val ResolverInvocationObservation.variableArgumentCount: Int
    get() = (occurrencePath.last() as ObjectEngineResult.ObjectKey).arguments.variableArgumentNames().size

/** Owning occurrences of variables used by the invocation's original argument expressions. */
val ResolverInvocationObservation.variableResolverOccurrenceIds: Set<ResolverOccurrenceId>
    get() = (occurrencePath.last() as ObjectEngineResult.ObjectKey).arguments.usedVariables()
        .mapNotNullTo(linkedSetOf()) { it.instanceId?.resolverOccurrenceId }
