package semantics

import graphql.schema.GraphQLTypeUtil
import model.requireQueryTypeDef
import model.requireType
import model.requireObjectField
import model.Arguments
import semantics.contract.selectionValues
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import model.EngineErrorData
import model.EngineIDResult
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.MaterializeSelection
import model.ObjectEngineResult
import model.outputType
import model.PathComponent
import viaduct.graphql.schema.ViaductSchema
import model.fragmentFrom
import viaduct.graphql.schema.graphqljava.gjDef
import model.materializeSelectionForestOf
import model.testing.TestWorld
import model.testing.occurrenceStampOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import viaduct.engine.api.EngineObjectData

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
                    world.schema.requireObjectField("Query", "value"),
                    emptyMap(),
                )
            val selections =
                world
                    .fragmentFrom("fragment ignored on Query { value }")
                    .materializeSelections
            val result =
                ObjectEngineResult.of(
                    type = world.schema.requireQueryTypeDef(),
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
        val result = ObjectEngineResult.of(world.schema.requireQueryTypeDef())

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
        val childType = world.schema.requireType("Child") as ViaductSchema.Object
        val childKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "child"),
                emptyMap(),
            )
        val valueKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Child", "value"),
                emptyMap(),
            )
        val reader: List<PathComponent> = listOf(childKey, valueKey)
        val childResult = ObjectEngineResult.of(childType, mutable = true)
        val valueCell = childResult.reserveCell(valueKey)
        valueCell.createValuePromise()
        val result =
            ObjectEngineResult.of(
                type = world.schema.requireQueryTypeDef(),
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
    fun `Node bridges do not escape into tenant-visible materialized input`() =
        runBlocking {
            val world =
                TestWorld
                    .fromSDL(
                        """
                        interface Node {
                          id: ID!
                        }

                        type User implements Node {
                          id: ID!
                        }

                        type Parent {
                          user: Node!
                          users: [Node]!
                        }

                        type Query {
                          parent: Parent!
                        }
                        """.trimIndent(),
                    ).assumptions
            val parent = world.schema.requireType("Parent") as ViaductSchema.Object
            val user = world.schema.requireType("User") as ViaductSchema.Object
            val bridge = world.schema.requireType("User_V_A_Bridge") as ViaductSchema.Object
            val producer =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Parent", "user_V_A_node"),
                    emptyMap(),
                )
            val listProducer =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Parent", "users_V_A_node"),
                    emptyMap(),
                )
            val payload =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("User_V_A_Bridge", "node"),
                    emptyMap(),
                )
            val id =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("User", "id"),
                    emptyMap(),
                )
            val userResult =
                ObjectEngineResult.of(
                    type = user,
                    values = mapOf(id to EngineIDResult.of("user-1")),
                )
            val bridgeResult =
                ObjectEngineResult.of(
                    type = bridge,
                    values = mapOf(payload to userResult),
                )
            val parentResult =
                ObjectEngineResult.of(
                    type = parent,
                    values =
                        mapOf(
                            producer to bridgeResult,
                            listProducer to
                                ListEngineResult.of(
                                    typeExpr = listProducer.field.outputType.unwrapList()!!,
                                    values =
                                        listOf(
                                            bridgeResult,
                                            null,
                                            ErrorEngineResult,
                                        ),
                                ),
                        ),
                )
            val selections =
                world
                    .fragmentFrom(
                        "fragment ParentInput on Parent { user { id } users { id } }",
                    )
                    .materializeSelections

            val materialized =
                context(world, runtimeSupport) {
                    parentResult.materialize(selections, emptyList())
                }

            assertSame(parent.gjDef, materialized.type)
            assertNotNull(materialized.type.getFieldDefinition("user"))
            assertNull(materialized.type.getFieldDefinition("user_V_A_node"))
            val nested = assertIs<EngineObjectData.Sync>(materialized.get("user"))
            assertEquals("User", nested.type.name)
            assertSame(user.gjDef, nested.type)
            assertEquals("user-1", nested.get("id"))
            assertEquals(
                "Node",
                GraphQLTypeUtil
                    .unwrapAll(materialized.type.getFieldDefinition("user").type)
                    .name,
            )

            val users = assertIs<List<*>>(materialized.get("users"))
            assertEquals(3, users.size)
            val listedUser = assertIs<EngineObjectData.Sync>(users[0])
            assertSame(user.gjDef, listedUser.type)
            assertEquals("user-1", listedUser.get("id"))
            assertNull(users[1])
            assertSame(EngineErrorData, users[2])
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
            val field = world.schema.requireObjectField("Query", "value")
            val storedKey = ObjectEngineResult.GroundKey.of(field, emptyMap())
            val selections =
                materializeSelectionForestOf(
                    MaterializeSelection.of(
                        responseKey = "first",
                        key = storedKey,
                        possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                        subselections = materializeSelectionForestOf(),
                    ),
                    MaterializeSelection.of(
                        responseKey = "second",
                        key = storedKey,
                        possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                        subselections = materializeSelectionForestOf(),
                    ),
                )
            val result =
                ObjectEngineResult.of(
                    type = world.schema.requireQueryTypeDef(),
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
            val field = world.schema.requireObjectField("Query", "value")
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
                        possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                        subselections = materializeSelectionForestOf(),
                    ),
                    MaterializeSelection.of(
                        responseKey = "second",
                        key = second,
                        possibleTypes = setOf(world.schema.requireQueryTypeDef()),
                        subselections = materializeSelectionForestOf(),
                    ),
                )
            val result =
                ObjectEngineResult.of(
                    type = world.schema.requireQueryTypeDef(),
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
