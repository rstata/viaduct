package execution

import graphql.GraphQL
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManualOERExecutionStrategyTest {
    @Test
    fun `completes queries from one prebuilt random OER`() {
        val world = TestWorld.fromSDL(SCHEMA).assumptions
        val runtimeWiring =
            RuntimeWiring
                .newRuntimeWiring()
                .wiringFactory(QPlanWiringFactory(world.schema))
                .build()
        val graphQLSchema =
            SchemaGenerator().makeExecutableSchema(
                SchemaParser().parse(SCHEMA),
                runtimeWiring,
            )
        val strategy =
            QPlanExecutionStrategy(
                assumptions = world,
                graphQLSchema = graphQLSchema,
                randomSeed = 8675309L,
            )
        val graphQL =
            GraphQL
                .newGraphQL(graphQLSchema)
                .queryExecutionStrategy(strategy)
                .build()

        val first = graphQL.execute("{ answer account: viewer { name active } }")
        val second = graphQL.execute("{ answer account: viewer { name active } }")

        assertTrue(first.errors.isEmpty(), first.errors.joinToString { it.message })
        val firstData = first.getData<Map<String, Any?>>()
        val secondData = second.getData<Map<String, Any?>>()
        assertEquals(expectedData(strategy.resolvedRoot!!, world.schema), firstData)
        assertEquals(firstData, secondData)
    }

    @Test
    fun `completes scalar and object lists from the prebuilt OER`() {
        val (graphQL, strategy) = graphQLFor(LIST_SCHEMA, randomSeed = 21L)

        val result =
            graphQL.execute(
                """
                {
                  labels
                  users {
                    id
                    name
                  }
                }
                """.trimIndent(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        val data = assertNotNull(result.getData<Map<String, Any?>>())
        assertEquals(2, (data.getValue("labels") as List<*>).size)
        assertEquals(2, (data.getValue("users") as List<*>).size)
        val root = strategy.resolvedRoot!!
        assertIs<ListEngineResult>(root.value(root.type, "labels"))
        assertIs<ListEngineResult>(root.value(root.type, "users"))
    }

    @Test
    fun `completes a source Node field through its lowered bridge OER`() {
        val (graphQL, strategy) = graphQLFor(NODE_SCHEMA, randomSeed = 22L)

        val result =
            graphQL.execute(
                """
                {
                  featured {
                    id
                    ... on User {
                      name
                    }
                  }
                }
                """.trimIndent(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        val featured =
            assertNotNull(result.getData<Map<String, Map<String, Any?>>>())
                .getValue("featured")
        assertEquals(setOf("id", "name"), featured.keys)
        val bridge = assertIs<ObjectEngineResult>(
            strategy.resolvedRoot!!.value(
                strategy.resolvedRoot!!.type,
                "featured_V_A_node",
            ),
        )
        assertEquals("Node_V_A_Bridge", bridge.type.typeName)
    }

    @Test
    fun `completes a list of Nodes through lowered bridge OERs`() {
        val (graphQL, strategy) = graphQLFor(NODE_LIST_SCHEMA, randomSeed = 23L)

        val result =
            graphQL.execute(
                """
                {
                  featured {
                    id
                    ... on User {
                      name
                    }
                  }
                }
                """.trimIndent(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        val featured =
            assertNotNull(result.getData<Map<String, List<Map<String, Any?>>>>())
                .getValue("featured")
        assertEquals(2, featured.size)
        assertTrue(featured.all { value -> value.keys == setOf("id", "name") })
        val bridges = assertIs<ListEngineResult>(
            strategy.resolvedRoot!!.value(
                strategy.resolvedRoot!!.type,
                "featured_V_A_node",
            ),
        )
        assertEquals(2, bridges.size)
        assertTrue(
            bridges.all { cell ->
                (cell.getValue().get() as ObjectEngineResult).type.typeName ==
                    "Node_V_A_Bridge"
            },
        )
    }

    @Test
    fun `rejects field arguments outside the manual OER milestone`() {
        val arguments = TestWorld.fromSDL(ARGUMENT_SCHEMA).assumptions
        val argumentGraphQLSchema = SchemaGenerator.createdMockedSchema(ARGUMENT_SCHEMA)
        assertFailsWith<IllegalArgumentException> {
            QPlanExecutionStrategy(arguments, argumentGraphQLSchema)
        }
    }

    private fun graphQLFor(
        schemaSDL: String,
        randomSeed: Long,
    ): Pair<GraphQL, QPlanExecutionStrategy> {
        val world = TestWorld.fromSDL(schemaSDL).assumptions
        val runtimeWiring =
            RuntimeWiring
                .newRuntimeWiring()
                .wiringFactory(QPlanWiringFactory(world.schema))
                .build()
        val graphQLSchema =
            SchemaGenerator().makeExecutableSchema(
                SchemaParser().parse(schemaSDL),
                runtimeWiring,
            )
        val strategy =
            QPlanExecutionStrategy(
                assumptions = world,
                graphQLSchema = graphQLSchema,
                randomSeed = randomSeed,
            )
        return GraphQL
            .newGraphQL(graphQLSchema)
            .queryExecutionStrategy(strategy)
            .build() to strategy
    }

    private fun expectedData(
        root: ObjectEngineResult,
        schema: Schema,
    ): Map<String, Any?> {
        val answer = root.value(schema, "answer")
        val viewer = assertIs<ObjectEngineResult>(root.value(schema, "viewer"))
        return mapOf(
            "answer" to answer,
            "account" to
                mapOf(
                    "name" to viewer.value(schema, "name"),
                    "active" to viewer.value(schema, "active"),
                ),
        )
    }

    private fun ObjectEngineResult.value(
        schema: Schema,
        fieldName: String,
    ): Any? {
        val field = schema.objectField(type.typeName, fieldName)
        val key = ObjectEngineResult.GroundKey.of(field, emptyMap())
        return getCell(key).getValue().get()
    }

    private fun ObjectEngineResult.value(
        type: Schema.ObjectType,
        fieldName: String,
    ): Any? {
        val field = type.fields.getValue(fieldName)
        val key = ObjectEngineResult.GroundKey.of(field, emptyMap())
        return getCell(key).getValue().get()
    }

    private companion object {
        val SCHEMA =
            """
            type Query {
              answer: Int!
              viewer: User!
            }

            type User {
              name: String!
              active: Boolean!
            }
            """.trimIndent()

        val ARGUMENT_SCHEMA =
            """
            type Query {
              greeting(name: String!): String
            }
            """.trimIndent()

        val LIST_SCHEMA =
            """
            type Query {
              labels: [String!]!
              users: [User!]!
            }

            type User {
              id: ID!
              name: String!
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
              featured: Node!
            }
            """.trimIndent()

        val NODE_LIST_SCHEMA =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
            }

            type Query {
              featured: [Node!]!
            }
            """.trimIndent()
    }
}
