package semantics.resolver26

import model.ObjectEngineResult
import model.PathComponent

/** Stable identity and location of one Resolver26 object-result occurrence. */
internal class OEROccurrenceContext(
    val root: ObjectEngineResult,
    path: List<PathComponent>,
    val target: ObjectEngineResult,
    val parent: OEROccurrenceContext? = null,
) {
    val path: List<PathComponent> = path.toList()

    fun coordinate(key: ObjectEngineResult.ObjectKey): List<PathComponent> = path + key
}
