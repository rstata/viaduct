package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import model.EngineObjectOrErrorData
import model.ListEngineResult
import model.ResolverOccurrenceId
import model.engineObjectDataOf
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.testRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueryValuesStateTest {
    @Test
    fun `fetch suspends until the declared Query value completes`(): Unit =
        runBlocking {
            val fixture = Fixture()
            val state = QueryValuesState()
            val releaseProducer = CompletableDeferred<Unit>()

            state.declare(fixture.occurrence)
            val producer =
                state.launchProducer(this, fixture.occurrence) {
                    releaseProducer.await()
                    fixture.queryValue
                }
            val fetched = async { state.fetch(fixture.occurrence) }
            assertFalse(fetched.isCompleted)

            releaseProducer.complete(Unit)
            producer.join()
            assertEquals(
                fixture.queryValue,
                assertIs<EngineObjectOrErrorData.Success>(fetched.await()).value,
            )
        }

    @Test
    fun `Query value declarations are strict and producer launch requires declaration`(): Unit =
        runBlocking {
            val fixture = Fixture()
            val state = QueryValuesState()

            assertFailsWith<NoSuchElementException> {
                state.launchProducer(this, fixture.occurrence) { fixture.queryValue }
            }
            assertFailsWith<NoSuchElementException> {
                state.fetch(fixture.occurrence)
            }

            state.declare(fixture.occurrence)
            assertFailsWith<IllegalStateException> { state.declare(fixture.occurrence) }
        }

    @Test
    fun `producer exception becomes an explicit error outcome for its owning field task`() {
        val fixture = Fixture()
        val state = QueryValuesState()
        val failure = IllegalStateException("Query producer failed")
        state.declare(fixture.occurrence)

        runBlocking {
            val producer =
                state.launchProducer(this, fixture.occurrence) {
                    throw failure
                }
            producer.join()
            val fetched =
                assertIs<EngineObjectOrErrorData.Error>(
                    state.fetch(fixture.occurrence),
                )

            assertSame(failure, fetched.error.cause)
        }
    }

    @Test
    fun `cancellation before producer entry terminates the declared Query value`(): Unit =
        runBlocking {
            val fixture = Fixture()
            val state = QueryValuesState()
            val requestJob = Job()
            val requestScope = CoroutineScope(requestJob)
            var producerStarted = false
            state.declare(fixture.occurrence)
            requestJob.cancel(CancellationException("request cancelled before dispatch"))

            val producer =
                state.launchProducer(requestScope, fixture.occurrence) {
                    producerStarted = true
                    fixture.queryValue
                }
            joinAll(producer)
            val failure =
                assertFailsWith<CancellationException> {
                    state.fetch(fixture.occurrence)
                }

            assertFalse(producerStarted)
            assertTrue(failure.message.orEmpty().contains("request cancelled before dispatch"))
        }

    private class Fixture {
        private val world = TestWorld.fromSDL("type Query { value: Int }").assumptions
        val occurrence =
            ResolverOccurrenceId.at(
                world.schema.testRoot(),
                listOf(ListEngineResult.Index.of(0)),
            )
        val queryValue = engineObjectDataOf(world.schema.requireQueryTypeDef())
    }
}
