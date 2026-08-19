package model

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromiseTest {
    @Test
    fun `completed promise returns its value and rejects completion`() =
        runBlocking {
            val promise = Promise.of("ready")

            assertTrue(promise.isCompleted)
            assertEquals("ready", promise.get())
            assertEquals("ready", promise.await())
            assertFailsWith<IllegalStateException> {
                promise.complete("again")
            }
        }

    @Test
    fun `deferred promise throws from get and resumes await after completion`() =
        runBlocking {
            val promise = Promise.ofDeferred<String>()
            val awaited = async { promise.await() }

            assertFalse(promise.isCompleted)
            assertFailsWith<UncompletedPromiseException> {
                promise.get()
            }
            assertFalse(awaited.isCompleted)

            promise.complete("ready")

            assertTrue(promise.isCompleted)
            assertEquals("ready", awaited.await())
            assertEquals("ready", promise.get())
            assertFailsWith<IllegalStateException> {
                promise.complete("again")
            }
        }

    @Test
    fun `failed promise throws its cause from get and await`() =
        runBlocking {
            val promise = Promise.ofDeferred<String>()
            val failure = NoSuchElementException("missing")

            promise.fail(failure)

            assertFailsWith<NoSuchElementException> { promise.get() }
            assertFailsWith<NoSuchElementException> { promise.await() }
            assertFailsWith<IllegalStateException> { promise.complete("late") }
            assertFailsWith<IllegalStateException> { promise.fail(failure) }
        }

    @Test
    fun `field promise validates before completion`() {
        val schema =
            TestWorld
                .fromSDL(
                    """
                    type Query { required: String! }
                    """.trimIndent(),
                ).schema
        val field =
            ObjectEngineResult.GroundKey.of(
                schema.requireObjectField("Query", "required"),
                emptyMap(),
            )
        val promise =
            ObjectEngineResult
                .of(schema.requireQueryTypeDef(), mutable = true)
                .reserveCell(field)
                .createValuePromise()

        assertFailsWith<IllegalArgumentException> {
            promise.complete(null)
        }
        assertFailsWith<UncompletedPromiseException> {
            promise.get()
        }

        promise.complete("ready")
        assertEquals("ready", promise.get())
    }
}
