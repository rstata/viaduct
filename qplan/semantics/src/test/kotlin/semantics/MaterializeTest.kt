package semantics

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.ObjectEngineResult
import model.MissingFieldException
import model.PathComponent
import model.Schema
import model.StringEngineResult
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
    private val runtimeSupport = RuntimeSupport.noCycleChecking()

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
                ObjectEngineResult.GroundKey.of(
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
                ObjectEngineResult.of(
                    type = world.schema.query,
                    mutable = true,
                )
            val promise = result.reserveCell(field).createValuePromise()
            val materialized =
                async(start = CoroutineStart.UNDISPATCHED) {
                    context(world, runtimeSupport) {
                        result.materialize(
                            selections = selections,
                            reader = emptyList(),
                        )
                    }
                }

            assertFalse(materialized.isCompleted)
            promise.complete(StringEngineResult.of("ready"))

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
        val result = ObjectEngineResult.of(world.schema.query)

        assertFailsWith<MissingFieldException> {
            runBlocking {
                context(world, runtimeSupport) {
                    result.materialize(
                        selections = selections,
                        reader = emptyList(),
                    )
                }
            }
        }
    }

    @Test
    fun `nested materialization checks a cycle before awaiting`() {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Query { child: Child! }
                    type Child { value: String! }
                    """.trimIndent(),
                ).assumptions
        val childType = world.schema.type("Child") as Schema.ObjectType
        val childKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "child"),
                emptyMap(),
            )
        val valueKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Child", "value"),
                emptyMap(),
            )
        val reader: List<PathComponent> = listOf(childKey, valueKey)
        val childResult = ObjectEngineResult.of(childType, mutable = true)
        val valueCell = childResult.reserveCell(valueKey)
        valueCell.createValuePromise()
        val result =
            ObjectEngineResult.of(
                type = world.schema.query,
                values = mapOf(childKey to childResult),
            )
        val selections =
            context(world) {
                world
                    .fragmentFrom("fragment ignored on Query { child { value } }")
                    .subselections
                    .merge(world.schema.query)
                    .instantiateBindings()
            }
        val cycleCheckingSupport =
            RuntimeSupport.cycleChecking { completedSelections ->
                completedSelections
            }
        cycleCheckingSupport.registerWriter(
            cell = valueCell,
            writer = reader,
        )

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                runBlocking {
                    context(world, cycleCheckingSupport) {
                        result.materialize(
                            selections = selections,
                            reader = reader,
                        )
                    }
                }
            }

        assertEquals(listOf(reader, reader), failure.cycle)
    }
}
