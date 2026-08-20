package execution

import execution.testing.ExecutionTestFixture
import model.engineResultOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QPlanWiringFactoryTest {
    @Test
    fun `vanilla GraphQL execution completes a resolved OER tree`() {
        val world = TestWorld.fromSDL(SCHEMA).assumptions
        val friend =
            world.engineResultOf("User") {
                "id" resolvesTo "user-2"
                "role" resolvesTo "MEMBER"
                "tags" resolvesTo emptyList<String>()
                "friends" resolvesTo emptyList<Any>()
            }
        val user =
            world.engineResultOf("User") {
                "id".resolvesTo("user-1", accessResult = false)
                "role" resolvesTo "ADMIN"
                "tags" resolvesTo listOf("engineer", null)
                "friends" resolvesTo listOf(friend)
            }
        val root =
            world.engineResultOf("Query") {
                field("user", "id" to "user-1").resolvesTo(
                    value = user,
                    accessResult = false,
                )
            }
        val fixture =
            ExecutionTestFixture.fromResolvedRoot(
                schemaSDL = SCHEMA,
                schema = world.schema,
                root = root,
            )

        val result =
            fixture.runQuery(
                """
                query User(${'$'}id: ID!) {
                  account: user(id: ${'$'}id) {
                    id
                    role
                    tags
                    friends {
                      id
                      role
                    }
                  }
                }
                """.trimIndent(),
                variables = mapOf("id" to "user-1"),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "account" to
                    mapOf(
                        "id" to "user-1",
                        "role" to "ADMIN",
                        "tags" to listOf("engineer", null),
                        "friends" to
                            listOf(
                                mapOf(
                                    "id" to "user-2",
                                    "role" to "MEMBER",
                                ),
                            ),
                    ),
            ),
            result.getData(),
        )
    }

    @Test
    fun `source Node fields complete through lowered bridge results`() {
        val world = TestWorld.fromSDL(NODE_SCHEMA).assumptions
        val user =
            world.engineResultOf("User") {
                "id" resolvesTo "user-1"
                "name" resolvesTo "Ada"
            }
        val bridge =
            world.engineResultOf("User_V_A_Bridge") {
                "id" resolvesTo "user-1"
                "node" resolvesTo user
            }
        val root =
            world.engineResultOf("Query") {
                field("node_V_A_node", "id" to "user-1") resolvesTo bridge
            }
        val fixture =
            ExecutionTestFixture.fromResolvedRoot(
                schemaSDL = NODE_SCHEMA,
                schema = world.schema,
                root = root,
            )

        val result =
            fixture.runQuery(
                """
                query {
                  node(id: "user-1") {
                    id
                    ... on User {
                      name
                    }
                  }
                }
                """.trimIndent(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "node" to
                    mapOf(
                        "id" to "user-1",
                        "name" to "Ada",
                    ),
            ),
            result.getData(),
        )
    }

    private companion object {
        val SCHEMA =
            """
            enum Role {
              ADMIN
              MEMBER
            }

            type Query {
              user(id: ID!, greeting: String = "hello"): User!
            }

            type User {
              id: ID!
              role: Role!
              tags: [String]
              friends: [User!]!
            }
            """.trimIndent()

        val NODE_SCHEMA =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
            }

            type Query {
              node(id: ID!): Node
            }
            """.trimIndent()
    }
}
