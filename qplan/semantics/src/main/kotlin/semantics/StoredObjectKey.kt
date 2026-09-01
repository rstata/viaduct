package semantics

import model.Assumptions
import model.ObjectEngineResult
import model.Stamp
import model.groundedArguments
import model.isContextuallyGrounded

/**
 * Finds the cell addressed by one contextually grounded selection key.
 *
 * Symbolic identity wins when the result retains it. Older resolver families may instead store
 * the key's grounded projection.
 */
context(world: Assumptions)
internal fun ObjectEngineResult.findStoredKey(
    candidate: ObjectEngineResult.ObjectKey,
): ObjectEngineResult.ObjectKey? {
    if (!candidate.isContextuallyGrounded()) return null
    if (candidate in keys) return candidate
    val arguments = candidate.groundedArguments()
    val occurrenceStamp = candidate.stamp as? Stamp.Occurrence
    val grounded =
        if (occurrenceStamp == null) {
            ObjectEngineResult.GroundKey.of(candidate.field, arguments)
        } else {
            ObjectEngineResult.GroundKey.of(occurrenceStamp, candidate.field, arguments)
        }
    return grounded.takeIf { it in keys }
}
