package model

/** An identity assigned once to a selection occurrence in a resolver registry. */
class SelectionOccurrenceId private constructor(
    private val sourceKeys: Set<ObjectEngineResult.Key>,
) {
    internal constructor(sourceKey: ObjectEngineResult.Key) : this(setOf(sourceKey))

    val sourceKey: ObjectEngineResult.Key
        get() = sourceKeys.first()

    internal fun represents(sourceKey: ObjectEngineResult.Key): Boolean =
        sourceKey in sourceKeys

    companion object {
        internal fun forResponseGroup(
            sourceKeys: Set<ObjectEngineResult.Key>,
        ): SelectionOccurrenceId {
            require(sourceKeys.isNotEmpty()) {
                "A response-group occurrence requires at least one source key"
            }
            return SelectionOccurrenceId(sourceKeys)
        }
    }
}

/**
 * Runtime treatment of a key or variable template.
 *
 * Equality is structural. [VariableFreeOccurrence] is one singleton value. Two [Occurrence] stamps
 * are equal when their resolver paths and occurrence-ID sequences are equal; occurrence IDs use
 * reference identity.
 */
sealed interface Stamp {
    /** A key whose selection contains no variables requiring occurrence identity. */
    data object VariableFreeOccurrence : Stamp

    /** One concrete resolver occurrence, optionally refined by Resolver26's selection lineage. */
    sealed interface Occurrence : Stamp {
        val resolverPath: List<PathComponent>
        val occurrenceLineage: List<SelectionOccurrenceId>

        /** The registry key represented by the final lineage member, when one exists. */
        val sourceKey: ObjectEngineResult.Key?

        companion object {
            fun of(
                resolverPath: List<PathComponent>,
                occurrenceLineage: List<SelectionOccurrenceId> = emptyList(),
            ): Occurrence = OccurrenceStampImpl(resolverPath, occurrenceLineage)
        }
    }
}

private data class OccurrenceStampImpl(
    override val resolverPath: List<PathComponent>,
    override val occurrenceLineage: List<SelectionOccurrenceId>,
) : Stamp.Occurrence {
    override val sourceKey: ObjectEngineResult.Key?
        get() = occurrenceLineage.lastOrNull()?.sourceKey
}

/**
 * Returns the stamped resolver occurrence that owns this source selection, or null when the owner
 * is the resolver occurrence identified directly by [Stamp.Occurrence.resolverPath].
 */
fun Stamp.Occurrence.ownerResolverStamp(): Stamp.Occurrence? =
    occurrenceLineage
        .dropLast(1)
        .takeIf { lineage -> lineage.isNotEmpty() }
        ?.let { lineage ->
            Stamp.Occurrence.of(
                resolverPath = resolverPath,
                occurrenceLineage = lineage,
            )
        }
