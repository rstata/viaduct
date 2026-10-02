package model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi

class UncompletedPromiseException : IllegalStateException("Promise has not been completed")

/** A write-once value that may be available immediately or completed later; equality is undefined. */
sealed interface Promise<T> {
    /** Whether this promise has completed, including completion with a null value. */
    val isCompleted: Boolean

    suspend fun await(): T

    /** @throws UncompletedPromiseException when this promise has not been completed */
    fun get(): T

    /** Atomically completes this promise, returning whether this call performed the transition. */
    fun complete(value: T): Boolean

    /** Atomically cancels this promise, returning whether this call performed the transition. */
    fun cancel(cause: CancellationException): Boolean

    companion object {
        fun <T> of(value: T): Promise<T> = CompletedPromiseImpl(value)

        fun <T> ofDeferred(): Promise<T> = DeferredPromiseImpl()
    }
}

internal fun <T> Promise.Companion.ofDeferred(
    validate: (T) -> Unit,
): Promise<T> = DeferredPromiseImpl(validate)

private class CompletedPromiseImpl<T>(private val value: T) : Promise<T>,
    ExceptionallyCompletablePromise {
    override val isCompleted: Boolean
        get() = true

    override suspend fun await(): T = value

    override fun get(): T = value

    override fun complete(value: T): Boolean = false

    override fun cancel(cause: CancellationException): Boolean = false

    override fun completeExceptionally(cause: Exception): Boolean = false
}

private class DeferredPromiseImpl<T>(private val validate: (T) -> Unit = {}) : Promise<T>,
    ExceptionallyCompletablePromise {
    private val deferred = CompletableDeferred<T>()

    override val isCompleted: Boolean
        get() = deferred.isCompleted

    override suspend fun await(): T = deferred.await()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun get(): T {
        if (!deferred.isCompleted) throw UncompletedPromiseException()
        return deferred.getCompleted()
    }

    override fun complete(value: T): Boolean {
        validate(value)
        return deferred.complete(value)
    }

    override fun cancel(cause: CancellationException): Boolean =
        completeExceptionally(cause)

    override fun completeExceptionally(cause: Exception): Boolean =
        deferred.completeExceptionally(cause)
}

/** Internal support for strict model promises that must wake readers with a protocol exception. */
internal interface ExceptionallyCompletablePromise {
    fun completeExceptionally(cause: Exception): Boolean
}

internal fun Promise<*>.completeExceptionally(cause: Exception): Boolean =
    (this as ExceptionallyCompletablePromise).completeExceptionally(cause)
