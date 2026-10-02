package viaduct.arbitrary.common

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.filterNot
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.shuffle
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay

/**
 * Return subsets of a given arb. Subsets will have a size determined by the provided
 * range, or if no range is provided, then subsets will contain between 0 and size-of-the-input-set
 * elements.
 */
fun <T> Arb<Set<T>>.subset(range: IntRange? = null): Arb<Set<T>> =
    flatMap { set ->
        val first = min(max(range?.first ?: 0, 0), set.size)
        val last = min(max(range?.last ?: 0, 0), set.size)

        Arb
            .pair(
                Arb.shuffle(set.toList()),
                Arb.int(first..last)
            ).map { (shuffled, count) ->
                shuffled.take(count).toSet()
            }
    }

/**
 * Return an Arb<Set> describing subsets of this Set.
 * @see Arb<Set<T>>.subset
 */
fun <T> Set<T>.arbSubset(range: IntRange? = null): Arb<Set<T>> = Arb.constant(this).subset(range = range)

/** Return an IntRange containing only this Int */
fun Int.asIntRange(): IntRange = IntRange(this, this)

/** Return a LongRange containing only this Int */
fun Int.asLongRange(): LongRange = this.toLong().asLongRange()

/** Return a LongRange containing only this Long */
fun Long.asLongRange(): LongRange = LongRange(this, this)

/**
 * Filter an Arb<IntRange> to only yield non-empty IntRange values.
 * This is different from [Arb.Companion.intRange], which can yield empty
 * IntRange values.
 */
fun Arb<IntRange>.nonEmpty(): Arb<IntRange> = this.filterNot { it.isEmpty() }

/** Return a new Arb containing only non-null values */
@Suppress("UNCHECKED_CAST")
fun <T> Arb<T?>.filterNotNull(): Arb<T> = filter { it != null }.map { it as T }

/** Generate an Arb that zips values of this arb with the values of another Arb */
fun <T, U> Arb<T>.zip(other: Arb<U>): Arb<Pair<T, U>> = Arb.bind(this, other) { t, u -> t to u }

/**
 * This method is a replacement for [Arb.Companion.choose]. This method will
 * never pick an Arb with a 0 weight. [Arb.Companion.choose] can, via edge cases, select an Arb
 * that is a assigned a weight of 0.
 */
fun <T> Arb.Companion.weightedChoose(
    weightedArb: Pair<Double, Arb<T>>,
    fallbackArb: Arb<T>
): Arb<T> {
    val weight = weightedArb.first
    WeightValidator(weight)?.let { throw IllegalArgumentException(it) }

    return when (weight) {
        0.0 -> fallbackArb
        1.0 -> weightedArb.second
        else -> {
            val intWeight = (weight * 1000).toInt()
            Arb.choose(
                intWeight to weightedArb.second,
                (1000 - intWeight) to fallbackArb
            )
        }
    }
}

/**
 * [Arb.Companion.choose] can, via edge cases, select an Arb that is assigned a weight of 0.
 * This method is a replacement for [Arb.Companion.choose] that will never pick an Arb with a 0 weight.
 */
fun <T> Arb.Companion.weightedChoose(arbs: List<Pair<Double, Arb<T>>>): Arb<T> {
    val weightedArbs = arbs
        .filter { (weight, _) -> weight > 0.0 }
        .map { (weight, arb) -> (weight * 1000).toInt() to arb }

    require(weightedArbs.size > 0)

    return if (weightedArbs.size == 1) {
        weightedArbs.first().second
    } else {
        Arb.choose(
            weightedArbs[0],
            weightedArbs[1],
            *weightedArbs.drop(2).toTypedArray()
        )
    }
}

/** A unit arb, that always returns Unit */
fun Arb.Companion.unit(): Arb<Unit> = Arb.of(Unit)

/**
 * Transform a Collection of Arb<T> into an Arb of List<T>.
 *
 * Example:
 *   val list = listOf(Arb.of(1), Arb.of(2), Arb.of(3))
 *   val items = list.collect().next(rs)   // listOf(1, 2, 3)
 */
fun <T> Collection<Arb<T>>.collect(): Arb<List<T>> = Arb.bind(this.toList()) { it }

/**
 * Get a boolean from this random source, with probability of a true
 * value being equal to the provided weight. Weights 0 and 1 consume no randomness.
 */
fun RandomSource.sampleWeight(weight: Double): Boolean =
    when (weight) {
        0.0 -> false
        1.0 -> true
        else -> random.nextDouble(0.0, 1.0) <= weight
    }

/**
 * Return an integer describing how many times the provided [CompoundingWeight]
 * was sampled before it hit its `max` sample count or returned false.
 */
fun RandomSource.count(weight: CompoundingWeight): Int {
    tailrec fun loop(count: Int): Int =
        if (count == weight.max) {
            count
        } else if (!sampleWeight(weight.weight)) {
            count
        } else {
            loop(count + 1)
        }
    return loop(0)
}

/** suspend for a value of milliseconds bounded by  [latencyMillis] */
internal suspend fun RandomSource.maybeDelay(latencyMillis: LongRange) {
    if (latencyMillis.last > 0) {
        val latencyMs = Arb.long(latencyMillis).next(this)
        delay(latencyMs)
    }
}

fun RandomSource.fork(): RandomSource = RandomSource.seeded(random.nextLong())
