package semantics

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.MissingFieldException
import model.Value
import model.fragmentFrom
import model.instantiateBindings
import model.merge
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MaterializeTest {
    @Test
    fun `materialization awaits a present deferred value`() =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        """
                        type Query { value: String! }
                        """.trimIndent(),
                    ).assumptions
            val field =
                Value.GroundKey.of(
                    world.schema.objectField("Query", "value"),
                    emptyMap(),
                )
            val selections =
                context(world) {
                    world
                        .fragmentFrom("fragment ignored on Query { value }")
                        .subselections
                        .merge(world.schema.query)
                        .instantiateBindings()
                }
            val result =
                EngineResult.Object.of(
                    type = world.schema.query,
                    mutable = true,
                )
            val promise = result.createValuePromise(field)
            val materialized =
                async(start = CoroutineStart.UNDISPATCHED) {
                    context(world) {
                        result.materialize(selections)
                    }
                }

            assertFalse(materialized.isCompleted)
            promise.complete(Value.String.of("ready"))

            assertEquals(
                Value.String.of("ready"),
                materialized.await().fieldValues.getValue(field),
            )
        }

    @Test
    fun `materialization rejects an absent value immediately`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Query { value: String! }
                    """.trimIndent(),
                ).assumptions
        val selections =
            context(world) {
                world
                    .fragmentFrom("fragment ignored on Query { value }")
                    .subselections
                    .merge(world.schema.query)
                    .instantiateBindings()
            }
        val result = EngineResult.Object.of(world.schema.query)

        assertFailsWith<MissingFieldException> {
            runBlocking {
                context(world) {
                    result.materialize(selections)
                }
            }
        }
    }
}
