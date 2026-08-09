package model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

class UncompletedPromiseException : IllegalStateException("Promise has not been completed")

/** A write-once value that may be available immediately or completed later; equality is undefined. */
sealed interface Promise<T> {
    suspend fun await(): T

    /** @throws UncompletedPromiseException when this promise has not been completed */
    fun get(): T

    /** @throws IllegalStateException when this promise has already been completed */
    fun complete(value: T)

    /** The producer identity retained by a deferred promise, or null for an immediate promise. */
    fun getDeferredStamp(): Any?

    companion object {
        fun <T> of(value: T): Promise<T> = CompletedPromiseImpl(value)

        fun <T> ofDeferred(deferredStamp: Any? = null): Promise<T> =
            DeferredPromiseImpl(deferredStamp)
    }
}

internal fun <T> Promise.Companion.ofDeferred(
    deferredStamp: Any?,
    validate: (T) -> Unit,
): Promise<T> = DeferredPromiseImpl(deferredStamp, validate)

private class CompletedPromiseImpl<T>(private val value: T) : Promise<T> {
    override suspend fun await(): T = value

    override fun get(): T = value

    override fun complete(value: T): Nothing =
        throw IllegalStateException("Promise has already been completed")

    override fun getDeferredStamp(): Any? = null
}

private class DeferredPromiseImpl<T>(
    private val deferredStamp: Any?,
    private val validate: (T) -> Unit = {},
) : Promise<T> {
    private val deferred = CompletableDeferred<T>()

    override suspend fun await(): T = deferred.await()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun get(): T {
        if (!deferred.isCompleted) throw UncompletedPromiseException()
        return deferred.getCompleted()
    }

    override fun complete(value: T) {
        check(!deferred.isCompleted) { "Promise has already been completed" }
        validate(value)
        check(deferred.complete(value)) { "Promise has already been completed" }
    }

    override fun getDeferredStamp(): Any? = deferredStamp
}
