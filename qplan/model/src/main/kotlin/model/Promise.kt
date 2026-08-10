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

    companion object {
        fun <T> of(value: T): Promise<T> = CompletedPromiseImpl(value)

        fun <T> ofDeferred(): Promise<T> = DeferredPromiseImpl()
    }
}

internal fun <T> Promise.Companion.ofDeferred(
    validate: (T) -> Unit,
): Promise<T> = DeferredPromiseImpl(validate)

private class CompletedPromiseImpl<T>(private val value: T) : Promise<T> {
    override suspend fun await(): T = value

    override fun get(): T = value

    override fun complete(value: T): Nothing =
        throw IllegalStateException("Promise has already been completed")
}

private class DeferredPromiseImpl<T>(private val validate: (T) -> Unit = {}) : Promise<T> {
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
}
