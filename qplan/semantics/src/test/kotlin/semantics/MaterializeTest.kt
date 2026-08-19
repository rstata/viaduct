package semantics

import model.Arguments

import semantics.contract.selectionValues

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.ListEngineResult
import model.MaterializeSelection
import model.ObjectEngineResult
import model.PathComponent
import model.Schema
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.testing.TestWorld
import model.testing.occurrenceStampOf
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
                world
                    .fragmentFrom("fragment ignored on Query { value }")
                    .materializeSelections
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
            promise.complete("ready")

            assertEquals(
                "ready",
                materialized.await().get("value"),
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
            world
                .fragmentFrom("fragment ignored on Query { value }")
                .materializeSelections
        val result = ObjectEngineResult.of(world.schema.query)

        assertFailsWith<NoSuchElementException> {
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
            world
                .fragmentFrom("fragment ignored on Query { child { value } }")
                .materializeSelections
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

    @Test
    fun `distinct response aliases can read one exact stored key`() =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        """
                        type Query { value: String! }
                        """.trimIndent(),
                    ).assumptions
            val field = world.schema.objectField("Query", "value")
            val storedKey = ObjectEngineResult.GroundKey.of(field, emptyMap())
            val selections =
                materializeSelectionForestOf(
                    MaterializeSelection.of(
                        responseKey = "first",
                        key = storedKey,
                        possibleTypes = setOf(world.schema.query),
                        subselections = materializeSelectionForestOf(),
                    ),
                    MaterializeSelection.of(
                        responseKey = "second",
                        key = storedKey,
                        possibleTypes = setOf(world.schema.query),
                        subselections = materializeSelectionForestOf(),
                    ),
                )
            val result =
                ObjectEngineResult.of(
                    type = world.schema.query,
                    values = mapOf(storedKey to "same"),
                )

            val materialized =
                context(world, runtimeSupport) {
                    result.materialize(selections, emptyList())
                }

            assertEquals(setOf("first", "second"), materialized.selectionValues().keys)
            assertEquals("same", materialized.selectionValues().getValue("first"))
            assertEquals("same", materialized.selectionValues().getValue("second"))
        }

    @Test
    fun `distinct response aliases read their exact occurrence keys`() =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        """
                        type Query { value: String! }
                        """.trimIndent(),
                    ).assumptions
            val field = world.schema.objectField("Query", "value")
            val arguments = Arguments.Resolved.of(field, emptyMap())
            val first =
                ObjectEngineResult.GroundKey.of(
                    occurrenceStampOf(listOf(ListEngineResult.Index.of(0))),
                    field,
                    arguments,
                )
            val second =
                ObjectEngineResult.GroundKey.of(
                    occurrenceStampOf(listOf(ListEngineResult.Index.of(1))),
                    field,
                    arguments,
                )
            val selections =
                materializeSelectionForestOf(
                    MaterializeSelection.of(
                        responseKey = "first",
                        key = first,
                        possibleTypes = setOf(world.schema.query),
                        subselections = materializeSelectionForestOf(),
                    ),
                    MaterializeSelection.of(
                        responseKey = "second",
                        key = second,
                        possibleTypes = setOf(world.schema.query),
                        subselections = materializeSelectionForestOf(),
                    ),
                )
            val result =
                ObjectEngineResult.of(
                    type = world.schema.query,
                    values =
                        mapOf(
                            first to "first-value",
                            second to "second-value",
                        ),
                )

            val materialized =
                context(world, runtimeSupport) {
                    result.materialize(selections, emptyList())
                }

            assertEquals(setOf("first", "second"), materialized.selectionValues().keys)
            assertEquals(
                "first-value",
                materialized.selectionValues().getValue("first"),
            )
            assertEquals(
                "second-value",
                materialized.selectionValues().getValue("second"),
            )
        }
}
