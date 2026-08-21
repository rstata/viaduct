package execution.viaductfeaturetests

// core/engine/runtime/src/test/kotlin/viaduct/engine/runtime/NodeResolverTest.kt
// Implemented 6 out of 13 tests as of 2026-08-20

import execution.testing.runQPlanFeatureTest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData

@ExperimentalCoroutinesApi
class NodeResolverTest {
    companion object {
        private val schemaSDL =
            """
            extend type Query {
                baz: Baz
                bazList: [Baz]!
            }
            type Baz implements Node {
                id: ID!
                x: Int
                x2: String
                y: String
                z: Int
                anotherBaz: Baz
            }
            """.trimIndent()
    }

    @Test
    fun `node resolver returns value`() {
        EngineTestModule(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference(
                            "1",
                            requireNotNull(schema.schema.getObjectType("Baz")),
                        )
                    }
                }
            }
            type("Baz") {
                nodeUnbatchedExecutor { _, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("x" to 42),
                    )
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{baz {x}}")
                .assertJson("""{"data": {"baz": {"x": 42}}}""")
        }
    }

    @Test
    @Disabled("Qplan currently requires all Nodes to be resolved by their node resolver")
    fun `node reference nested inside resolver response`() {
        EngineTestModule(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        createEngineObjectData(
                            requireNotNull(schema.schema.getObjectType("Baz")),
                            mapOf(
                                "x" to 1,
                                "anotherBaz" to
                                    ctx.createNodeReference(
                                        "2",
                                        requireNotNull(schema.schema.getObjectType("Baz")),
                                    ),
                            ),
                        )
                    }
                }
            }
            type("Baz") {
                nodeUnbatchedExecutor { _, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("x" to 99),
                    )
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{baz { x anotherBaz { x } }}")
                .assertJson("""{"data": {"baz": {"x": 1, "anotherBaz": {"x": 99}}}}""")
        }
    }

    @Test
    fun `node resolver is invoked for id-only resolution`() {
        var invoked = false
        EngineTestModule(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference(
                            "1",
                            requireNotNull(schema.schema.getObjectType("Baz")),
                        )
                    }
                }
            }
            type("Baz") {
                nodeUnbatchedExecutor { _, _, _ ->
                    invoked = true
                    createEngineObjectData(objectType, mapOf())
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ baz { id } }")
            assertTrue(invoked)
        }
    }

    @Test
    fun `node resolver throws`() {
        EngineTestModule(schemaSDL) {
            field("Query" to "baz") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference(
                            "1",
                            requireNotNull(schema.schema.getObjectType("Baz")),
                        )
                    }
                }
            }
            type("Baz") {
                nodeUnbatchedExecutor { _, _, _ ->
                    throw RuntimeException("msg")
                }
            }
        }.runQPlanFeatureTest {
            val result = runQuery("{ baz { x } }")
            assertEquals(mapOf("baz" to null), result.getData())
            assertTrue(result.errors.any { it.path == listOf("baz") })
        }
    }

    @Test
    fun `list of nodes`() {
        EngineTestModule(schemaSDL) {
            field("Query" to "bazList") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        (1..5).map {
                            ctx.createNodeReference(
                                it.toString(),
                                requireNotNull(schema.schema.getObjectType("Baz")),
                            )
                        }
                    }
                }
            }
            type("Baz") {
                nodeUnbatchedExecutor { id, _, _ ->
                    val internalId = id.toInt()
                    if (internalId % 2 == 0) {
                        throw RuntimeException("msg")
                    } else {
                        createEngineObjectData(objectType, mapOf("x" to internalId))
                    }
                }
            }
        }.runQPlanFeatureTest {
            val result = runQuery("{ bazList { x } }")
            val expectedResultData =
                mapOf(
                    "bazList" to
                        listOf(
                            mapOf("x" to 1),
                            null,
                            mapOf("x" to 3),
                            null,
                            mapOf("x" to 5),
                        ),
                )
            assertEquals(expectedResultData, result.getData())
            assertEquals(
                listOf(listOf("bazList", 1), listOf("bazList", 3)),
                result.errors.map { it.path },
            )
        }
    }

    @Test
    fun `node resolver does not batch`() {
        val execCounts = ConcurrentHashMap<String, AtomicInteger>()
        EngineTestModule(schemaSDL) {
            field("Query" to "bazList") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        (1..5).map {
                            ctx.createNodeReference(
                                it.toString(),
                                requireNotNull(schema.schema.getObjectType("Baz")),
                            )
                        }
                    }
                }
            }
            type("Baz") {
                nodeUnbatchedExecutor { id, _, _ ->
                    val internalId = id
                    execCounts
                        .computeIfAbsent(internalId) { AtomicInteger(0) }
                        .incrementAndGet()
                    createEngineObjectData(
                        objectType,
                        mapOf("x" to internalId.toInt()),
                    )
                }
            }
        }.runQPlanFeatureTest {
            val result = runQuery("{bazList { x }}")

            // Verify each node was resolved individually (not batched)
            assertEquals(
                mapOf("1" to 1, "2" to 1, "3" to 1, "4" to 1, "5" to 1),
                execCounts.mapValues { it.value.get() },
            )

            // Verify the results are correct
            val expectedData =
                mapOf(
                    "bazList" to
                        listOf(
                            mapOf("x" to 1),
                            mapOf("x" to 2),
                            mapOf("x" to 3),
                            mapOf("x" to 4),
                            mapOf("x" to 5),
                        ),
                )
            assertEquals(expectedData, result.getData())
        }
    }
}
