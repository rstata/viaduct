package execution

import graphql.GraphQL
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import model.Assumptions
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.engineResultOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NullAndErrorCompletionTest {
    @Test
    fun `completes nullable fields and list elements without errors`() {
        val world = TestWorld.fromSDL(SCHEMA).assumptions
        val user =
            world.engineResultOf("User") {
                "requiredName" resolvesTo "Ada"
                "optionalNote" resolvesTo null
            }
        val root =
            world.engineResultOf("Query") {
                "healthy" resolvesTo "ready"
                "nullableText" resolvesTo null
                "nullableUser" resolvesTo user
                "nullableItems" resolvesTo listOf("first", null, "third")
                "strictItems" resolvesTo listOf("first", "second", "third")
            }

        val result =
            graphQLFor(world, root).execute(
                """
                {
                  healthy
                  nullableText
                  nullableUser {
                    requiredName
                    optionalNote
                  }
                  nullableItems
                }
                """.trimIndent(),
            )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
        assertEquals(
            mapOf(
                "healthy" to "ready",
                "nullableText" to null,
                "nullableUser" to
                    mapOf(
                        "requiredName" to "Ada",
                        "optionalNote" to null,
                    ),
                "nullableItems" to listOf("first", null, "third"),
            ),
            result.getData(),
        )
    }

    @Test
    fun `reports errors and applies GraphQL null bubbling`() {
        val world = TestWorld.fromSDL(SCHEMA).assumptions
        val user =
            world.engineResultOf("User") {
                "requiredName" resolvesTo ErrorEngineResult
                "optionalNote" resolvesTo "unselected"
            }
        val root =
            world.engineResultOf("Query") {
                "healthy" resolvesTo "ready"
                "nullableText" resolvesTo ErrorEngineResult
                "nullableUser" resolvesTo user
                "nullableItems" resolvesTo
                    listOf("first", ErrorEngineResult, "third")
                "strictItems" resolvesTo
                    listOf("first", ErrorEngineResult, "third")
            }

        val result =
            graphQLFor(world, root).execute(
                """
                {
                  healthy
                  nullableText
                  nullableUser {
                    requiredName
                  }
                  nullableItems
                  strictItems
                }
                """.trimIndent(),
            )

        assertEquals(
            mapOf(
                "healthy" to "ready",
                "nullableText" to null,
                "nullableUser" to null,
                "nullableItems" to listOf("first", null, "third"),
                "strictItems" to null,
            ),
            result.getData(),
        )
        assertEquals(4, result.errors.size)
        assertEquals(
            setOf<List<Any>>(
                listOf("nullableText"),
                listOf("nullableUser", "requiredName"),
                listOf("nullableItems", 1),
                listOf("strictItems", 1),
            ),
            result.errors.mapTo(linkedSetOf()) { error -> assertNotNull(error.path) },
        )
        assertTrue(
            result.errors.all { error ->
                error.message.contains("QPlan field resolution failed")
            },
        )
    }

    private fun graphQLFor(
        assumptions: Assumptions,
        root: ObjectEngineResult,
    ): GraphQL {
        val runtimeWiring =
            RuntimeWiring
                .newRuntimeWiring()
                .wiringFactory(QPlanWiringFactory(assumptions.schema))
                .build()
        val graphQLSchema =
            SchemaGenerator().makeExecutableSchema(
                SchemaParser().parse(SCHEMA),
                runtimeWiring,
            )
        return GraphQL
            .newGraphQL(graphQLSchema)
            .queryExecutionStrategy(QPlanExecutionStrategy(assumptions, root))
            .build()
    }

    private companion object {
        val SCHEMA =
            """
            type Query {
              healthy: String!
              nullableText: String
              nullableUser: User
              nullableItems: [String]
              strictItems: [String!]
            }

            type User {
              requiredName: String!
              optionalNote: String
            }
            """.trimIndent()
    }
}
