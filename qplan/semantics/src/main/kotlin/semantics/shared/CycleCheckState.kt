package semantics.shared

import model.EngineResultCell
import model.PathComponent
import java.util.concurrent.ConcurrentHashMap

/** Tracks exact resolver reads and rejects cycles in the resulting writer dependency graph. */
interface CycleCheckState {
    fun registerWriter(
        cell: EngineResultCell,
        writer: List<PathComponent>,
    )

    fun cycleCheck(
        reader: List<PathComponent>,
        cell: EngineResultCell,
    )

    companion object {
        fun create(): CycleCheckState = CycleCheckStateImpl()

        fun createNOP(): CycleCheckState = NOPCycleCheckState
    }
}

internal class ResolverReadCycleException(
    val cycle: List<List<PathComponent>>,
) : IllegalStateException(
        "Resolver-read cycle: ${cycle.joinToString(separator = " -> ")}",
    )

private object NOPCycleCheckState : CycleCheckState {
    override fun registerWriter(
        cell: EngineResultCell,
        writer: List<PathComponent>,
    ) {}

    override fun cycleCheck(
        reader: List<PathComponent>,
        cell: EngineResultCell,
    ) {}
}

private class CycleCheckStateImpl : CycleCheckState {
    private val writersByCell =
        ConcurrentHashMap<EngineResultCell, List<PathComponent>>()
    private val readersByCell =
        ConcurrentHashMap<EngineResultCell, MutableSet<List<PathComponent>>>()
    private val readsByReader =
        ConcurrentHashMap<List<PathComponent>, MutableSet<List<PathComponent>>>()

    override fun registerWriter(
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

    override fun cycleCheck(
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
