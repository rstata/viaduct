package execution

import execution.testing.ExecutionTestFixture
import graphql.schema.idl.RuntimeWiring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QPlanExecutionStrategyTest {
    @Test
    fun `GraphQL execute enters qplan without invoking a data fetcher`() {
        var dataFetcherInvoked = false
        val runtimeWiring =
            RuntimeWiring
                .newRuntimeWiring()
                .type("Query") { type ->
                    type.dataFetcher("greeting") {
                        dataFetcherInvoked = true
                        error("GraphQL-Java field resolution must not run")
                    }
                }.build()
        val fixture = ExecutionTestFixture.fromSDL(SCHEMA, runtimeWiring)

        val result =
            fixture.runQuery(
                """
                query Greeting(${'$'}name: String!) {
                  greeting(name: ${'$'}name)
                }
                """.trimIndent(),
                variables = mapOf("name" to "Ada"),
            )

        assertTrue(result.errors.isEmpty())
        assertEquals(emptyMap<String, Any?>(), result.getData<Map<String, Any?>>())
        assertFalse(dataFetcherInvoked)
    }

    private companion object {
        val SCHEMA =
            """
            type Query {
              greeting(name: String!): String!
            }
            """.trimIndent()
    }
}
