package model.testing

import model.Schema
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class NodeBridgeLoweringTest {
    @Test
    fun `bridge lookup is nullable for ordinary and synthetic source fields`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type User implements Node {
                      id: ID!
                    }

                    type Query {
                      seed: ID!
                      user: User!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    mapOf(user to nodeResolverOf { error("Not invoked") })
                },
            )
        val schema = world.schema as GJSchema
        val source = schema.field("Query", "user")
        val bridge = schema.field("Query", "user\$bridge")

        assertSame(bridge, schema.nodeBridgeFieldOrNull(source))
        assertNull(schema.nodeBridgeFieldOrNull(schema.field("Query", "seed")))
        assertNull(schema.nodeBridgeFieldOrNull(bridge))
    }
}
