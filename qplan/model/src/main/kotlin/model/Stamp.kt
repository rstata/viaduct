package model

/** Runtime identity of a variable instance owned by one concrete resolver occurrence. */
sealed interface Stamp {
    /** One concrete resolver occurrence. */
    sealed interface Occurrence : Stamp {
        val resolverPath: List<PathComponent>

        companion object {
            fun of(
                resolverPath: List<PathComponent>,
            ): Occurrence = OccurrenceStampImpl(resolverPath)
        }
    }
}

private data class OccurrenceStampImpl(
    override val resolverPath: List<PathComponent>,
) : Stamp.Occurrence
