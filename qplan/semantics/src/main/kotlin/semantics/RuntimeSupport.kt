package semantics

import model.Assumptions
import model.EngineResult
import model.PathComponent
import model.SelectionForest
import model.Value
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime services shared by one resolution constructor.
 *
 * [complete] expands the selections visible at a resolver output boundary. Instances of these are
 * passed as context arguments to control how resolution works. The default writer registration and
 * cycle checking are no-ops for synchronous resolution and post-resolution operations.
 */
internal fun interface RuntimeSupport {
    context(world: Assumptions)
    fun complete(selections: SelectionForest): SelectionCompletion

    fun registerWriter(
        target: EngineResult.Object,
        key: Value.GroundKey,
        writer: List<PathComponent>,
    ) {}

    fun cycleCheck(
        reader: List<PathComponent>,
        target: EngineResult.Object,
        key: Value.GroundKey,
    ) {}

    companion object {
        fun noCycleChecking(): RuntimeSupport =
            RuntimeSupport { selections -> SelectionCompletion(selections) }

        fun cycleChecking(
            complete: RuntimeSupport = noCycleChecking(),
        ): RuntimeSupport =
            CycleCheckingRuntimeSupport(complete)
    }
}

internal class ResolverReadCycleException(
    val cycle: List<List<PathComponent>>,
) : IllegalStateException(
        "Resolver-read cycle: ${cycle.joinToString(separator = " -> ")}",
    )

private data class ValueSlot(
    val target: EngineResult.Object,
    val key: Value.GroundKey,
)

private class CycleCheckingRuntimeSupport(
    private val completionSupport: RuntimeSupport,
) : RuntimeSupport {
    private val writersBySlot =
        ConcurrentHashMap<ValueSlot, List<PathComponent>>()
    private val readsByReader =
        ConcurrentHashMap<List<PathComponent>, MutableSet<List<PathComponent>>>()

    context(world: Assumptions)
    override fun complete(selections: SelectionForest): SelectionCompletion =
        completionSupport.complete(selections)

    override fun registerWriter(
        target: EngineResult.Object,
        key: Value.GroundKey,
        writer: List<PathComponent>,
    ) {
        val slot = ValueSlot(target, key)
        val previous = writersBySlot.putIfAbsent(slot, writer.toList())
        check(previous == null) {
            "Writer already registered for ${key.field.fieldName}: $previous"
        }
    }

    override fun cycleCheck(
        reader: List<PathComponent>,
        target: EngineResult.Object,
        key: Value.GroundKey,
    ) {
        val writer = writersBySlot[ValueSlot(target, key)] ?: return
        val stableReader = reader.toList()
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
