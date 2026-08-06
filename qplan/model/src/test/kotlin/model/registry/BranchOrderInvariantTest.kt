package model.registry

import model.Fragment
import model.Schema
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.testing.FromObjectField
import model.testing.TestWorld
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BranchOrderInvariantTest {
    @Test
    fun `rejects a provider and use in the same structural branch`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = BRANCH_SCHEMA,
                    fieldResolvers = ::branchResolvers,
                    variableProviders = { schema ->
                        variable(
                            schema = schema,
                            name = "value",
                            provider = "fragment ignored on Query { shared { value } }",
                            responsePath = listOf("shared", "value"),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("Query contains a cycle shared -> shared"))
        assertTrue(failure.message!!.contains("provider path shared/value"))
        assertTrue(failure.message!!.contains("use path shared/consume"))
    }

    @Test
    fun `rejects a shared passive provider and use prefix`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Branch {
                          value: Int!
                          consume(arg: Int!): Int!
                        }

                        type Parent {
                          result: Int!
                          shared: Branch!
                        }

                        type Query {
                          parent: Parent!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.field("Query", "parent") to
                                resolver(schema.emptyFragmentOf("Query")),
                            schema.field("Parent", "result") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Parent {
                                          shared {
                                            value
                                            consume(arg: ${'$'}value)
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.field("Branch", "consume") to
                                resolver(schema.emptyFragmentOf("Branch")),
                        )
                    },
                    variableProviders = { schema ->
                        val owner = schema.field("Parent", "result") as Schema.ObjectField
                        mapOf(
                            Value.Variable.of("value", owner, path = null) to
                                schema.fromObjectField(
                                    "fragment ignored on Parent { shared { value } }",
                                    listOf("shared", "value"),
                                ),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("Parent contains a cycle shared -> shared"))
    }

    @Test
    fun `rejects overlap introduced by a transitive resolver prerequisite`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Child {
                          field(arg: Int!): Int!
                        }

                        type Query {
                          result: Int!
                          common: Int!
                          child: Child!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.field("Query", "result") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          child {
                                            field(arg: ${'$'}value)
                                          }
                                          common
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.field("Query", "common") to
                                resolver(
                                    schema.fragmentFrom(
                                        "fragment ignored on Query { child { field(arg: 1) } }",
                                    ),
                                ),
                            schema.field("Query", "child") to
                                resolver(schema.emptyFragmentOf("Query")),
                            schema.field("Child", "field") to
                                resolver(schema.emptyFragmentOf("Child")),
                        )
                    },
                    variableProviders = { schema ->
                        variable(
                            schema = schema,
                            name = "value",
                            provider = "fragment ignored on Query { common }",
                            responsePath = listOf("common"),
                        )
                    },
                )
            }

        assertTrue(
            failure.message!!.contains("Query contains a cycle child -> child"),
            failure.message,
        )
        assertTrue(failure.message!!.contains("production path child -> common"))
        assertTrue(failure.message!!.contains("variable \$value"))
    }

    @Test
    fun `rejects a cross-variable branch-order cycle`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = TWO_BRANCH_SCHEMA,
                    fieldResolvers = { schema ->
                        twoBranchResolvers(
                            schema,
                            """
                            fragment ignored on Query {
                              a {
                                value
                                consume(arg: ${'$'}v)
                              }
                              b {
                                value
                                consume(arg: ${'$'}w)
                              }
                            }
                            """.trimIndent(),
                        )
                    },
                    variableProviders = { schema ->
                        variable(
                            schema,
                            name = "v",
                            provider = "fragment ignored on Query { b { value } }",
                            responsePath = listOf("b", "value"),
                        ) +
                            variable(
                                schema,
                                name = "w",
                                provider = "fragment ignored on Query { a { value } }",
                                responsePath = listOf("a", "value"),
                            )
                    },
                )
            }

        assertTrue(
            failure.message!!.contains("Query contains a cycle a -> b -> a"),
            failure.message,
        )
        assertTrue(failure.message!!.contains("variable \$v"))
        assertTrue(failure.message!!.contains("variable \$w"))
    }

    @Test
    fun `accepts linear and independent variable branch orders`() {
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Branch {
                  value: Int!
                  consume(arg: Int!): Int!
                }

                type Query {
                  result: Int!
                  a: Branch!
                  b: Branch!
                  c: Branch!
                  d: Branch!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val emptyQuery = schema.emptyFragmentOf("Query")
                mapOf(
                    schema.field("Query", "result") to
                        resolver(
                            schema.fragmentFrom(
                                """
                                fragment ignored on Query {
                                  a { value }
                                  b {
                                    value
                                    consume(arg: ${'$'}w)
                                  }
                                  c { consume(arg: ${'$'}v) }
                                  d { consume(arg: ${'$'}independent) }
                                }
                                """.trimIndent(),
                            ),
                        ),
                    schema.field("Query", "a") to resolver(emptyQuery),
                    schema.field("Query", "b") to resolver(emptyQuery),
                    schema.field("Query", "c") to resolver(emptyQuery),
                    schema.field("Query", "d") to resolver(emptyQuery),
                    schema.field("Branch", "consume") to
                        resolver(schema.emptyFragmentOf("Branch")),
                )
            },
            variableProviders = { schema ->
                variable(
                    schema,
                    name = "w",
                    provider = "fragment ignored on Query { a { value } }",
                    responsePath = listOf("a", "value"),
                ) +
                    variable(
                        schema,
                        name = "v",
                        provider = "fragment ignored on Query { b { value } }",
                        responsePath = listOf("b", "value"),
                    ) +
                    variable(
                        schema,
                        name = "independent",
                        provider = "fragment ignored on Query { a { value } }",
                        responsePath = listOf("a", "value"),
                    )
            },
        )
    }

    @Test
    fun `collapses argument-distinct occurrences into one structural branch`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Branch {
                          value: Int!
                          consume(arg: Int!): Int!
                        }

                        type Query {
                          result: Int!
                          child(id: Int!): Branch!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.field("Query", "result") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          child(id: 1) { value }
                                          child(id: 2) { consume(arg: ${'$'}value) }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.field("Query", "child") to
                                resolver(schema.emptyFragmentOf("Query")),
                            schema.field("Branch", "consume") to
                                resolver(schema.emptyFragmentOf("Branch")),
                        )
                    },
                    variableProviders = { schema ->
                        variable(
                            schema = schema,
                            name = "value",
                            provider = "fragment ignored on Query { child(id: 1) { value } }",
                            responsePath = listOf("child", "value"),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("Query contains a cycle child -> child"))
    }

    @Test
    fun `finds variables nested in input objects and lists`() {
        TestWorld.fromSDL(
            schemaSDL =
                """
                input Config {
                  values: [Int!]!
                }

                type Query {
                  result: Int!
                  source: Int!
                  consume(config: Config!): Int!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                mapOf(
                    schema.field("Query", "result") to
                        resolver(
                            schema.fragmentFrom(
                                """
                                fragment ignored on Query {
                                  source
                                  consume(config: { values: [1, ${'$'}value] })
                                }
                                """.trimIndent(),
                            ),
                        ),
                    schema.field("Query", "source") to
                        resolver(schema.emptyFragmentOf("Query")),
                    schema.field("Query", "consume") to
                        resolver(schema.emptyFragmentOf("Query")),
                )
            },
            variableProviders = { schema ->
                variable(
                    schema = schema,
                    name = "value",
                    provider = "fragment ignored on Query { source }",
                    responsePath = listOf("source"),
                )
            },
        )
    }

    @Test
    fun `includes lowered node bridge prerequisites in provider production`() {
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
                  result: Int!
                  user: User!
                  consume(id: ID!): Int!
                }
                """.trimIndent(),
            nodeResolvers = { schema ->
                val user = schema.type("User") as Schema.ObjectType
                mapOf(user to model.testing.nodeResolverOf { error("Not invoked") })
            },
            fieldResolvers = { schema ->
                mapOf(
                    schema.field("Query", "result") to
                        resolver(
                            schema.fragmentFrom(
                                """
                                fragment ignored on Query {
                                  user { id }
                                  consume(id: ${'$'}id)
                                }
                                """.trimIndent(),
                            ),
                        ),
                    schema.field("Query", "user") to
                        resolver(schema.emptyFragmentOf("Query")),
                    schema.field("Query", "consume") to
                        resolver(schema.emptyFragmentOf("Query")),
                )
            },
            variableProviders = { schema ->
                variable(
                    schema = schema,
                    name = "id",
                    provider = "fragment ignored on Query { user { id } }",
                    responsePath = listOf("user", "id"),
                )
            },
        )
    }

    private companion object {
        val BRANCH_SCHEMA =
            """
            type Branch {
              value: Int!
              consume(arg: Int!): Int!
            }

            type Query {
              result: Int!
              shared: Branch!
            }
            """.trimIndent()

        val TWO_BRANCH_SCHEMA =
            """
            type Branch {
              value: Int!
              consume(arg: Int!): Int!
            }

            type Query {
              result: Int!
              a: Branch!
              b: Branch!
            }
            """.trimIndent()

        fun branchResolvers(schema: Schema): Map<Schema.OutputField, FieldResolver> =
            mapOf(
                schema.field("Query", "result") to
                    resolver(
                        schema.fragmentFrom(
                            """
                            fragment ignored on Query {
                              shared {
                                value
                                consume(arg: ${'$'}value)
                              }
                            }
                            """.trimIndent(),
                        ),
                    ),
                schema.field("Query", "shared") to resolver(schema.emptyFragmentOf("Query")),
                schema.field("Branch", "consume") to
                    resolver(schema.emptyFragmentOf("Branch")),
            )

        fun twoBranchResolvers(
            schema: Schema,
            resultFragment: String,
        ): Map<Schema.OutputField, FieldResolver> =
            mapOf(
                schema.field("Query", "result") to
                    resolver(schema.fragmentFrom(resultFragment)),
                schema.field("Query", "a") to resolver(schema.emptyFragmentOf("Query")),
                schema.field("Query", "b") to resolver(schema.emptyFragmentOf("Query")),
                schema.field("Branch", "consume") to
                    resolver(schema.emptyFragmentOf("Branch")),
            )

        fun variable(
            schema: Schema,
            name: String,
            provider: String,
            responsePath: List<String>,
        ): Map<Value.Variable, FromObjectField> {
            val owner = schema.field("Query", "result") as Schema.ObjectField
            return mapOf(
                Value.Variable.of(name, owner, path = null) to
                    schema.fromObjectField(provider, responsePath),
            )
        }

        fun resolver(fragment: Fragment): FieldResolver =
            model.testing.fieldResolverOf(fragment) { _, _ -> error("Not invoked") }
    }
}
