package semantics

import model.Assumptions
import model.EngineResult
import model.EngineResultCell
import model.PathComponent
import model.SelectionForest
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime services shared by one resolution constructor.
 *
 * [complete] expands the selections visible at a resolver output boundary. Instances of these are
 * passed as context arguments to control how resolution works. The default writer registration and
 * cycle checking are no-ops for synchronous resolution and post-resolution operations.
 */
internal fun interface ResolverSupport {
    context(world: Assumptions)
    fun complete(selections: SelectionForest): SelectionForest

    fun registerWriter(
        cell: EngineResultCell,
        writer: List<PathComponent>,
    ) {}

    fun cycleCheck(
        reader: List<PathComponent>,
        cell: EngineResultCell,
    ) {}

    companion object {
        fun noCycleChecking(completer: (SelectionForest) -> SelectionForest): ResolverSupport =
            ResolverSupport { selections -> completer(selections) }

        fun cycleChecking(
            completer: (SelectionForest) -> SelectionForest = {
                throw UnsupportedOperationException("ResolverSupport completion is not configured")
            },
        ): ResolverSupport =
            object : ResolverSupport {
                private val checker = CycleChecker()

                context(world: Assumptions)
                override fun complete(selections: SelectionForest): SelectionForest =
                    completer(selections)

                override fun registerWriter(
                    cell: EngineResultCell,
                    writer: List<PathComponent>,
                ) {
                    checker.registerWriter(cell, writer)
                }

                override fun cycleCheck(
                    reader: List<PathComponent>,
                    cell: EngineResultCell,
                ) {
                    checker.cycleCheck(reader, cell)
                }
            }
    }
}

internal class ResolverReadCycleException(
    val cycle: List<List<PathComponent>>,
) : IllegalStateException(
        "Resolver-read cycle: ${cycle.joinToString(separator = " -> ")}",
    )

private class CycleChecker {
    private val writersByCell =
        ConcurrentHashMap<EngineResultCell, List<PathComponent>>()
    private val readersByCell =
        ConcurrentHashMap<EngineResultCell, MutableSet<List<PathComponent>>>()
    private val readsByReader =
        ConcurrentHashMap<List<PathComponent>, MutableSet<List<PathComponent>>>()

    fun registerWriter(
        cell: EngineResultCell,
        writer: List<PathComponent>,
    ) {
        val stableWriter = writer.toList()
        val previous = writersByCell.putIfAbsent(cell, stableWriter)
        check(previous == null) {
            "Writer already registered for cell: $previous"
        }
        readersByCell[cell].orEmpty().forEach { reader ->
            addRead(reader, stableWriter)
        }
    }

    fun cycleCheck(
        reader: List<PathComponent>,
        cell: EngineResultCell,
    ) {
        val stableReader = reader.toList()
        readersByCell
            .computeIfAbsent(cell) {
                ConcurrentHashMap.newKeySet()
            }.add(stableReader)
        writersByCell[cell]?.let { writer ->
            addRead(stableReader, writer)
        }
    }

    @Synchronized
    private fun addRead(
        stableReader: List<PathComponent>,
        writer: List<PathComponent>,
    ) {
        readsByReader
            .computeIfAbsent(stableReader) {
                // One coroutine normally owns a reader's set; keep it concurrent defensively.
                ConcurrentHashMap.newKeySet()
            }.add(writer)

        pathFrom(writer, stableReader)?.let { writerToReader ->
            throw ResolverReadCycleException(listOf(stableReader) + writerToReader)
        }
    }

    private fun pathFrom(
        start: List<PathComponent>,
        destination: List<PathComponent>,
    ): List<List<PathComponent>>? {
        if (start == destination) return listOf(start)

        val pending = ArrayDeque<List<PathComponent>>()
        val visited = mutableSetOf(start)
        val predecessor = mutableMapOf<List<PathComponent>, List<PathComponent>>()
        pending += start
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            readsByReader[current].orEmpty().forEach { successor ->
                if (!visited.add(successor)) return@forEach
                predecessor[successor] = current
                if (successor == destination) {
                    return reconstructPath(start, destination, predecessor)
                }
                pending += successor
            }
        }
        return null
    }
}

private fun reconstructPath(
    start: List<PathComponent>,
    destination: List<PathComponent>,
    predecessor: Map<List<PathComponent>, List<PathComponent>>,
): List<List<PathComponent>> {
    val reversed = mutableListOf(destination)
    var current = destination
    while (current != start) {
        current = checkNotNull(predecessor[current])
        reversed += current
    }
    return reversed.asReversed()
}
