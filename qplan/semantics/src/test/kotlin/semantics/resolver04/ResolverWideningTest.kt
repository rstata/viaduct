package semantics.resolver04

import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Former widening worlds that the depth-first variable stratification invariant now rejects.
 */
class ResolverWideningTest {
    @Test
    fun `rejects a node branch shared by a variable provider and use`() {
        var nodeApplications = 0
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type Child implements Node {
                      id: ID!
                      passive: Boolean!
                      computed(arg: String!): Int!
                    }

                    type Container {
                      child: Child!
                    }

                    type Query {
                      container: Container!
                      result: Int!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    val child = schema.type("Child") as Schema.ObjectType
                    mapOf(
                        child to
                            model.testing.nodeResolverOf { id ->
                                nodeApplications += 1
                                schema.objectOf("Child") {
                                    "id" setTo id
                                    "passive" setTo true
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "container") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("Container") {
                                    "child" setTo
                                        schema.objectOf("Child") {
                                            "id" setTo "child-1"
                                        }
                                }
                            },
                        schema.field("Query", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      container {
                                        child {
                                          computed(arg: ${'$'}value)
                                          __typename
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ -> Value.Int.of(1) },
                        schema.field("Child", "computed") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Child { passive }",
                                ),
                            ) { _, _ -> Value.Int.of(2) },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        Value.Variable.of("value", schema.field("Query", "result") as Schema.ObjectField, path = null) to
                            schema.provider(
                                """
                                fragment ignored on Query {
                                  container {
                                    child {
                                      __typename
                                    }
                                  }
                                }
                                """.trimIndent(),
                                "container",
                                "child",
                                "__typename",
                            ),
                    )
                    },
                )
            }

        assertTrue(failure.message!!.contains("container -> container"))
        assertEquals(0, nodeApplications)
    }

    @Test
    fun `rejects transitive provider work that enters the variable use branch`() {
        val applications = linkedMapOf<String, Int>()
        fun applied(name: String) {
            applications[name] = applications.getOrDefault(name, 0) + 1
        }
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Object1 {
                      variableConsumer: Int!
                      common: String!
                      child: Object2!
                    }

                    type Object2 {
                      field2(arg: String): Int!
                    }

                    type Query {
                      source: Object1!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "source") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                applied("source")
                                schema.objectOf("Object1")
                            },
                        schema.field("Object1", "common") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Object1 {
                                      child {
                                        field2(arg: "literal")
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ ->
                                applied("common")
                                Value.String.of("bound")
                            },
                        schema.field("Object1", "variableConsumer") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Object1 {
                                      child {
                                        field2(arg: ${'$'}value)
                                      }
                                      common
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ ->
                                applied("variableConsumer")
                                Value.Int.of(1)
                            },
                        schema.field("Object1", "child") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Object1"),
                            ) { _, _ ->
                                applied("child")
                                schema.objectOf("Object2")
                            },
                        schema.field("Object2", "field2") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Object2"),
                            ) { _, _ ->
                                applied("field2")
                                Value.Int.of(2)
                            },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        Value.Variable.of("value", schema.field("Object1", "variableConsumer") as Schema.ObjectField, path = null) to
                            schema.provider(
                                "fragment ignored on Object1 { common }",
                                "common",
                            ),
                    )
                    },
                )
            }

        assertTrue(failure.message!!.contains("child -> child"))
        assertTrue(failure.message!!.contains("production path child -> common"))
        assertEquals(
            emptyMap(),
            applications,
            "Registry rejection must precede every resolver application",
        )
    }
}
