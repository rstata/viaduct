package execution.viaductfeaturetests

// core/engine/runtime/src/test/kotlin/viaduct/engine/runtime/fixtures/EngineFeatureTestExample.kt
// Implemented 2 out of 8 tests as of 2026-08-20

import execution.testing.runQPlanFeatureTest
import kotlin.test.Test
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData

class EngineFeatureTestExample {
    @Test
    fun `simple resolver test`() {
        val schemaSDL =
            """
            extend type Query {
                hello: String
                number: Int
                withArgs(name: String!): String
            }
            """.trimIndent()

        EngineTestModule(schemaSDL) {
            fieldWithValue("Query" to "hello", "world")
            fieldWithValue("Query" to "number", 42)
            field("Query" to "withArgs") {
                resolver {
                    fn { args, _, _, _, _ ->
                        "Hello, ${args["name"]}!"
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("""{ hello number withArgs(name: "Alice") }""")
                .assertJson(
                    """{"data": {"hello": "world", "number": 42, "withArgs": "Hello, Alice!"}}""",
                )
        }
    }

    @Test
    fun `node resolver test`() {
        val schemaSDL =
            """
            type TestNode implements Node {
                id: ID!
                name: String
            }
            """.trimIndent()

        EngineTestModule(schemaSDL) {
            field("Query" to "node") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference(
                            "123",
                            requireNotNull(schema.schema.getObjectType("TestNode")),
                        )
                    }
                }
            }
            type("TestNode") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id, "name" to "Test Node $id"),
                    )
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ node(id: 123) { id ... on TestNode { name } } }")
                .assertJson("""{data: { node: { id: "123", name: "Test Node 123"} } }""")
        }
    }
}
