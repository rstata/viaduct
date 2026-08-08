package model

import java.util.concurrent.ConcurrentHashMap

/**
 * A thread-safe store in which each key can be written exactly once.
 *
 * Absence is distinct from a stored null value when [V] is nullable. Reads of absent keys and
 * repeated writes are outside the store's domain.
 */
internal class OnceStore<K : Any, V> {
    private val values = ConcurrentHashMap<K, Any>()

    fun isSet(key: K): Boolean = values.containsKey(key)

    /** @throws IllegalStateException when [key] has not been written */
    fun read(key: K): V {
        val storedValue = values[key]
        check(storedValue != null) { "$key not found" }
        @Suppress("UNCHECKED_CAST")
        return if (storedValue === NULL_PROXY) null as V else storedValue as V
    }

    /** @throws IllegalStateException when [key] has already been written */
    fun write(
        key: K,
        value: V,
    ) {
        val previous = values.putIfAbsent(key, value ?: NULL_PROXY)
        check(previous == null) { "$key already written" }
    }

    private data object NULL_PROXY
}
