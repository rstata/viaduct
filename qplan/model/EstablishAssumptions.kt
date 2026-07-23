package model

/**
 * Stands for a value fixed by the assumptions of a reasoning exercise.
 *
 * This model has no executable initialization for such values. A declaration initialized with this
 * function denotes the single value stipulated by the exercise's assumptions, with the declared
 * type [T]. Executing the declaration is outside the model and therefore always throws.
 */
fun <T> establishAssumptions(): T =
    throw RuntimeException("See KDoc for why this throws.")
