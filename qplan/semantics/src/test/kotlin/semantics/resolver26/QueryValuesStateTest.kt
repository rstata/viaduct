package semantics.resolver26

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.EngineErrorData
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

            state.declare(fixture.occurrence)
            val fetched = async { state.fetch(fixture.occurrence) }
            assertFalse(fetched.isCompleted)

            assertTrue(
                state.complete(
                    fixture.occurrence,
                    EngineObjectOrErrorData.of(fixture.queryValue),
                ),
            )
            assertEquals(
                fixture.queryValue,
                assertIs<EngineObjectOrErrorData.Success>(fetched.await()).value,
            )
        }

    @Test
    fun `Query value declarations and transitions require declaration`(): Unit =
        runBlocking {
            val fixture = Fixture()
            val state = QueryValuesState()

            assertFailsWith<NoSuchElementException> {
                state.complete(
                    fixture.occurrence,
                    EngineObjectOrErrorData.of(fixture.queryValue),
                )
            }
            assertFailsWith<NoSuchElementException> {
                state.cancel(fixture.occurrence, CancellationException("cancelled"))
            }
            assertFailsWith<NoSuchElementException> {
                state.fetch(fixture.occurrence)
            }

            state.declare(fixture.occurrence)
            assertFailsWith<IllegalStateException> { state.declare(fixture.occurrence) }
        }

    @Test
    fun `an explicit error outcome is fetched as a value`() {
        val fixture = Fixture()
        val state = QueryValuesState()
        val failure = IllegalStateException("Query producer failed")
        state.declare(fixture.occurrence)

        runBlocking {
            assertTrue(
                state.complete(
                    fixture.occurrence,
                    EngineObjectOrErrorData.of(EngineErrorData.of(failure)),
                ),
            )
            val fetched =
                assertIs<EngineObjectOrErrorData.Error>(
                    state.fetch(fixture.occurrence),
                )

            assertSame(failure, fetched.error.cause)
        }
    }

    @Test
    fun `cancellation terminates the declared Query value`(): Unit =
        runBlocking {
            val fixture = Fixture()
            val state = QueryValuesState()
            state.declare(fixture.occurrence)
            val cancellation = CancellationException("request cancelled")

            assertTrue(state.cancel(fixture.occurrence, cancellation))
            assertFalse(state.cancel(fixture.occurrence, cancellation))
            val failure =
                assertFailsWith<CancellationException> {
                    state.fetch(fixture.occurrence)
                }

            assertEquals(cancellation.message, failure.message)
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
