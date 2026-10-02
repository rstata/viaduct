package viaduct.engine.runtime2.arbitrary

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next

/** Keep the Kotest draw sequence stable for seeded generator replay. */
internal fun RandomSource.chance(weight: Double): Boolean = Arb.double(0.0, 1.0).next(this) < weight

/** Draw each index in turn, including the final singleton draw. */
internal fun <T> List<T>.shuffled(random: RandomSource): List<T> {
    val remaining = toMutableList()
    val result = mutableListOf<T>()
    while (remaining.isNotEmpty()) {
        result += remaining.removeAt(Arb.int(0 until remaining.size).next(random))
    }
    return result
}
