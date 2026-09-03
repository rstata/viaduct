package semantics.resolver26

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

class QueryValuesStateTest {
    @Test
    fun `fetch suspends until the declared Query value completes`(): Unit =
        runBlocking {
            val fixture = Fixture()
            val state = QueryValuesState()

            state.declare(fixture.occurrence)
            val fetched = async { state.fetch(fixture.occurrence) }
            assertFalse(fetched.isCompleted)

            state.complete(fixture.occurrence, fixture.queryValue)
            assertEquals(fixture.queryValue, fetched.await())
        }

    @Test
    fun `Query value transitions are strict`() {
        val fixture = Fixture()
        val state = QueryValuesState()

        assertFailsWith<NoSuchElementException> {
            state.complete(fixture.occurrence, fixture.queryValue)
        }
        assertFailsWith<NoSuchElementException> {
            runBlocking { state.fetch(fixture.occurrence) }
        }

        state.declare(fixture.occurrence)
        assertFailsWith<IllegalStateException> { state.declare(fixture.occurrence) }
        state.complete(fixture.occurrence, fixture.queryValue)
        assertFailsWith<IllegalStateException> {
            state.complete(fixture.occurrence, fixture.queryValue)
        }
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
