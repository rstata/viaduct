package semantics

import model.Assumptions
import model.SelectionForest

/**
 * SPI for supplying the output-boundary policy for one resolution constructor.
 *
 * [complete] expands the selections visible at a resolver output boundary. Instances of these are
 * passed as context arguments to control how resolution works.
 */
internal fun interface RuntimeSupport {
    context(world: Assumptions)
    fun complete(selections: SelectionForest): SelectionCompletion
}
