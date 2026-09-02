package execution

import execution.testing.runQPlanFeatureTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldBatchResolverExecutor
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createEngineObjectData

class EngineTestModuleQPlanFeatureTest {
    @Test
    fun `executes field executors with arguments and object required selections`() {
        EngineTestModule(
            """
            extend type Query {
              base: Int!
              total(extra: Int!): Int!
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "base", 5)
            field("Query" to "total") {
                resolver {
                    objectSelections("base")
                    fn { args, objectValue, _, _, _ ->
                        objectValue.get("base") as Int + args.getValue("extra") as Int
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ total(extra: 4) }").assertJson("{data: {total: 9}}")
        }
    }

    @Test
    fun `executes field executors with Query required selections`() {
        EngineTestModule(
            """
            extend type Query {
              base: Int!
              total: Int!
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "base", 5)
            field("Query" to "total") {
                resolver {
                    querySelections("base")
                    fn { _, _, queryValue, _, _ ->
                        queryValue.get("base") as Int + 4
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ total }").assertJson("{data: {total: 9}}")
        }
    }

    @Test
    fun `passes qplan output demand to selective field executors`() {
        var requestedFields: Set<String>? = null
        EngineTestModule(
            """
            extend type Query { viewer: User! }
            type User { name: String!, age: Int! }
            """.trimIndent(),
        ) {
            field("Query" to "viewer") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                        unbatchedResolveFn = { _, _, _, selections, _ ->
                            requestedFields =
                                requireNotNull(selections)
                                    .selections()
                                    .mapTo(linkedSetOf()) { it.fieldName }
                            createEngineObjectData(
                                requireNotNull(schema.schema.getObjectType("User")),
                                mapOf("name" to "Ada"),
                            )
                        },
                    )
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ viewer { name } }").assertJson("{data: {viewer: {name: \"Ada\"}}}")
        }

        assertEquals(setOf("name"), requestedFields)
    }

    @Test
    fun `supports named fragments in object required selections`() {
        EngineTestModule(
            """
            extend type Query {
              base: Int!
              total: Int!
            }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "base", 5)
            field("Query" to "total") {
                resolver {
                    objectSelections(
                        """
                        fragment Base on Query { base }
                        fragment Main on Query { ...Base }
                        """.trimIndent(),
                    )
                    fn { _, objectValue, _, _, _ ->
                        objectValue.get("base") as Int
                    }
                }
            }
        }.runQPlanFeatureTest {
            runQuery("{ total }").assertJson("{data: {total: 5}}")
        }
    }

    @Test
    fun `completes typename through qplan lowering`() {
        EngineTestModule(
            """
            extend type Query {
              viewer: User!
            }

            type User {
              id: ID!
            }
            """.trimIndent(),
        ) {
            field("Query" to "viewer") {
                value(
                    createEngineObjectData(
                        graphQLObjectType = requireNotNull(schema.schema.getObjectType("User")),
                        data = mapOf("id" to "u1"),
                    ),
                )
            }
        }.runQPlanFeatureTest {
            val result = runQuery("{ viewer { __typename id } }")

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(
                mapOf("viewer" to mapOf("__typename" to "User", "id" to "u1")),
                result.getData(),
            )
        }
    }

    @Test
    fun `lowers field node references and invokes node executors`() {
        val globalId = java.util.Base64.getEncoder().encodeToString("User:u1".toByteArray())
        EngineTestModule(
            """
            extend type Query {
              viewer: User!
            }

            type User implements Node {
              id: ID!
              name: String!
            }
            """.trimIndent(),
        ) {
            field("Query" to "viewer") {
                valueFromContext { context ->
                    context.createNodeReference(
                        globalId,
                        requireNotNull(schema.schema.getObjectType("User")),
                    )
                }
            }
            type("User") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id, "name" to "Ada"),
                    )
                }
            }
        }.runQPlanFeatureTest {
            val result = runQuery("{ viewer { __typename id name } }")

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(
                mapOf(
                    "viewer" to
                        mapOf(
                            "__typename" to "User",
                            "id" to globalId,
                            "name" to "Ada",
                        ),
                ),
                result.getData(),
            )
        }
    }

    @Test
    fun `supports built in Query node without dispatchers`() {
        val globalId = java.util.Base64.getEncoder().encodeToString("User:u1".toByteArray())
        EngineTestModule(
            """
            type User implements Node {
              id: ID!
              name: String!
            }
            """.trimIndent(),
        ) {
            type("User") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id, "name" to "Grace"),
                    )
                }
            }
        }.runQPlanFeatureTest {
            val result =
                runQuery(
                    """
                    query Node(${'$'}id: ID!) {
                      node(id: ${'$'}id) {
                        __typename
                        id
                        ... on User { name }
                      }
                    }
                    """.trimIndent(),
                    mapOf("id" to globalId),
                )

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(
                mapOf(
                    "node" to
                        mapOf(
                            "__typename" to "User",
                            "id" to globalId,
                            "name" to "Grace",
                        ),
                ),
                result.getData(),
            )
        }
    }

    @Test
    fun `rejects batching before constructing qplan`() {
        val module =
            EngineTestModule(
                """
                extend type Query {
                  value: Int
                }
                """.trimIndent(),
            ) {
                field("Query" to "value") {
                    resolverExecutor {
                        MockFieldBatchResolverExecutor(resolverId = resolverId)
                    }
                }
            }

        val error =
            assertFailsWith<NotImplementedError> {
                module.runQPlanFeatureTest {}
            }
        assertTrue(error.message.orEmpty().contains("batching field executor Query.value"))
    }
}
