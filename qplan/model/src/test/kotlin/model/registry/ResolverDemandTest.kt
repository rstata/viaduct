package model.registry

import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.VariableCoordinate
import model.emptyFragmentOf
import model.fragmentFrom
import model.selectionForestOf
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResolverDemandTest {
    @Test
    fun `includes field-relative variables in the resolver demand graph`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      x: Int
                      y(b: Int): Int
                      z(c: Int): Int
                      raw: Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "x") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      y(b: ${'$'}b)
                                      z(c: ${'$'}c)
                                      raw
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.field("Query", "y") to resolver(schema.emptyFragmentOf("Query")),
                        schema.field("Query", "z") to resolver(schema.emptyFragmentOf("Query")),
                        schema.field("Query", "raw") to resolver(schema.emptyFragmentOf("Query")),
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.field("Query", "x") as Schema.ObjectField
                    mapOf(
                        VariableCoordinate.of(owner, Value.Variable.of("b")) to
                            schema.fragmentFrom(
                                """
                                fragment ignored on Query {
                                  z(c: ${'$'}c)
                                }
                                """.trimIndent(),
                            ).subselections.single(),
                        VariableCoordinate.of(owner, Value.Variable.of("c")) to
                            schema.fragmentFrom(
                                """
                                fragment ignored on Query {
                                  raw
                                }
                                """.trimIndent(),
                            ).subselections.single(),
                    )
                },
            )
        val schema = world.schema
        val registry = world.executorRegistry
        val x = schema.field("Query", "x")
        val y = schema.field("Query", "y")
        val z = schema.field("Query", "z")
        val raw = schema.field("Query", "raw")
        val b = registry.variableCoordinate(Value.Variable.of("b"))
        val c = registry.variableCoordinate(Value.Variable.of("c"))

        assertEquals(setOf(y, z, raw, b, c), registry.mayDemandFrom(x))
        assertEquals(setOf(z, c), registry.mayDemandFrom(b))
        assertEquals(setOf(raw), registry.mayDemandFrom(c))
        assertEquals(setOf(x), registry.mayBeDemandedBy(b))
        assertEquals(setOf(x, b), registry.mayBeDemandedBy(c))
    }

    @Test
    fun `rejects repeated global variable names and variable cycles`() {
        val schemaSDL =
            """
            type Query {
              x: Int
              y: Int
              z(a: Int, b: Int): Int
            }
            """.trimIndent()
        val duplicate =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = schemaSDL,
                    fieldResolvers = { schema ->
                        val resolvers =
                            schema.query.fields.values
                            .filter { it.fieldName != "__typename" }
                            .associateWith { resolver(schema.emptyFragmentOf("Query")) }
                            .toMutableMap()
                        resolvers[schema.field("Query", "x")] =
                            resolver(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { y }",
                                ),
                            )
                        resolvers[schema.field("Query", "y")] =
                            resolver(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { x }",
                                ),
                            )
                        resolvers
                    },
                    variableProviders = { schema ->
                        mapOf(
                            VariableCoordinate.of(
                                schema.field("Query", "x") as Schema.ObjectField,
                                Value.Variable.of("same"),
                            ) to
                                schema.fragmentFrom(
                                    "fragment ignored on Query { y }",
                                ).subselections.single(),
                            VariableCoordinate.of(
                                schema.field("Query", "y") as Schema.ObjectField,
                                Value.Variable.of("same"),
                            ) to
                                schema.fragmentFrom(
                                    "fragment ignored on Query { x }",
                                ).subselections.single(),
                        )
                    },
                )
            }
        assertTrue(duplicate.message!!.contains("globally unique"))

        val cycle =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = schemaSDL,
                    fieldResolvers = { schema ->
                        val resolvers =
                            schema.query.fields.values
                            .filter { it.fieldName != "__typename" }
                            .associateWith { resolver(schema.emptyFragmentOf("Query")) }
                            .toMutableMap()
                        resolvers[schema.field("Query", "x")] =
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      z(a: ${'$'}a, b: 0)
                                      z(a: 0, b: ${'$'}b)
                                    }
                                    """.trimIndent(),
                                ),
                            )
                        resolvers
                    },
                    variableProviders = { schema ->
                        val owner = schema.field("Query", "x") as Schema.ObjectField
                        mapOf(
                            VariableCoordinate.of(owner, Value.Variable.of("a")) to
                                schema.fragmentFrom(
                                    "fragment ignored on Query { z(a: 0, b: ${'$'}b) }",
                                ).subselections.single(),
                            VariableCoordinate.of(owner, Value.Variable.of("b")) to
                                schema.fragmentFrom(
                                    "fragment ignored on Query { z(a: ${'$'}a, b: 0) }",
                                ).subselections.single(),
                        )
                    },
                )
            }
        assertTrue(cycle.message!!.contains("demand cycle"))
    }

    @Test
    fun `rejects provider paths outside their defining resolver fragment`() {
        val absent =
            assertFailsWith<IllegalArgumentException> {
                providerContainmentWorld(
                    ownerFragment =
                        """
                        fragment ignored on Query {
                          consume(value: ${'$'}value)
                        }
                        """.trimIndent(),
                    providerFragment = "fragment ignored on Query { source(id: 1) }",
                )
            }
        assertTrue(absent.message!!.contains("not contained"))

        val wrongRoot =
            assertFailsWith<IllegalArgumentException> {
                providerContainmentWorld(
                    ownerFragment =
                        """
                        fragment ignored on Query {
                          consume(value: ${'$'}value)
                          source(id: 1)
                        }
                        """.trimIndent(),
                    providerFragment = "fragment ignored on Payload { value }",
                )
            }
        assertTrue(wrongRoot.message!!.contains("not relative"))

        val argumentDistinct =
            assertFailsWith<IllegalArgumentException> {
                providerContainmentWorld(
                    ownerFragment =
                        """
                        fragment ignored on Query {
                          consume(value: ${'$'}value)
                          source(id: 1)
                        }
                        """.trimIndent(),
                    providerFragment = "fragment ignored on Query { source(id: 2) }",
                )
            }
        assertTrue(argumentDistinct.message!!.contains("not contained"))
    }

    @Test
    fun `rejects a provider path with a guard absent from its defining fragment`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        interface Subject {
                          value: Int!
                        }

                        type First implements Subject {
                          value: Int!
                        }

                        type Second implements Subject {
                          value: Int!
                        }

                        type Query {
                          result: Int!
                          consume(value: Int!): Int!
                          subject: Subject!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.field("Query", "result") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          consume(value: ${'$'}value)
                                          subject {
                                            ... on First {
                                              value
                                            }
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.field("Query", "consume") to
                                resolver(schema.emptyFragmentOf("Query")),
                            schema.field("Query", "subject") to
                                resolver(schema.emptyFragmentOf("Query")),
                        )
                    },
                    variableProviders = { schema ->
                        val owner = schema.field("Query", "result") as Schema.ObjectField
                        mapOf(
                            VariableCoordinate.of(owner, Value.Variable.of("value")) to
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      subject {
                                        ... on Second {
                                          value
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                ).subselections.single(),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("not contained"))
    }

    @Test
    fun `rejects an exact fragment that retargets its contained provider path`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result(selector: Int!): Int!
                      consume(value: Int!): Int!
                      source(id: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val representative =
                        schema.fragmentFrom(
                            """
                            fragment ignored on Query {
                              consume(value: ${'$'}value)
                              source(id: 1)
                            }
                            """.trimIndent(),
                        )
                    val source = schema.field("Query", "source")
                    mapOf(
                        schema.field("Query", "result") to
                            Resolver.Field.ofArgumentRetargeting(
                                objectFragment = representative,
                                retargetArguments = { key, _ ->
                                    if (key.field == source) {
                                        Value.Arguments.of(source, mapOf("id" to 2))
                                    } else {
                                        key.arguments
                                    }
                                },
                                function = { _, _ -> Value.Int.of(1) },
                            ),
                        schema.field("Query", "consume") to
                            resolver(schema.emptyFragmentOf("Query")),
                        schema.field("Query", "source") to
                            resolver(schema.emptyFragmentOf("Query")),
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.field("Query", "result") as Schema.ObjectField
                    mapOf(
                        VariableCoordinate.of(owner, Value.Variable.of("value")) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { source(id: 1) }",
                            ).subselections.single(),
                    )
                },
            )
        val result = world.schema.field("Query", "result")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                world.executorRegistry
                    .resolver(result)
                    .objectFragment(
                        Value.Arguments.of(result, mapOf("selector" to 2)),
                    )
            }

        assertTrue(failure.message!!.contains("not contained"))
    }

    @Test
    fun `derives resolver demand from all reachable selections and their possible types`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = DEMAND_SCHEMA,
                nodeResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    val admin = schema.type("Admin") as Schema.ObjectType
                    mapOf(
                        user to model.testing.nodeResolverOf { error("Not invoked") },
                        admin to model.testing.nodeResolverOf { error("Not invoked") },
                    )
                },
                fieldResolvers = { schema ->
                    val consumerFragment =
                        schema.fragmentFrom(
                            """
                            fragment ignored on Query {
                              node {
                                resolved {
                                  value
                                }
                              }
                            }
                            """.trimIndent(),
                        )
                    val outerFragment =
                        schema.fragmentFrom(
                            """
                            fragment ignored on Query {
                              consumer {
                                value
                              }
                            }
                            """.trimIndent(),
                        )
                    mapOf(
                        schema.field("Query", "node") to resolver(schema.emptyFragmentOf("Query")),
                        schema.field("Query", "consumer") to
                            resolver(consumerFragment),
                        schema.field("Query", "outer") to resolver(outerFragment),
                        schema.field("User", "resolved") to
                            resolver(schema.emptyFragmentOf("User")),
                        schema.field("Admin", "resolved") to
                            resolver(schema.emptyFragmentOf("Admin")),
                    )
                },
            )
        val schema = world.schema
        val registry = world.executorRegistry
        val user = schema.type("User") as Schema.ObjectType
        val admin = schema.type("Admin") as Schema.ObjectType
        val queryNode = schema.field("Query", "node")
        val queryNodeId = schema.field("Query", "node\$id")
        val consumer = schema.field("Query", "consumer")
        val outer = schema.field("Query", "outer")
        val userResolved = schema.field("User", "resolved")
        val adminResolved = schema.field("Admin", "resolved")

        assertEquals(
            setOf(queryNode, userResolved, adminResolved),
            registry.mayDemandFrom(consumer),
        )
        assertEquals(setOf(consumer), registry.mayDemandFrom(outer))
        assertEquals(setOf(queryNodeId), registry.mayDemandFrom(queryNode))
        assertTrue(registry.mayDemandFrom(queryNodeId).isEmpty())
        assertTrue(registry.mayDemandFrom(userResolved).isEmpty())
        assertTrue(registry.mayDemandFrom(adminResolved).isEmpty())

        assertEquals(setOf(outer), registry.mayBeDemandedBy(consumer))
        assertEquals(setOf(consumer), registry.mayBeDemandedBy(queryNode))
        assertEquals(setOf(queryNode), registry.mayBeDemandedBy(queryNodeId))
        assertEquals(setOf(consumer), registry.mayBeDemandedBy(userResolved))
        assertEquals(setOf(consumer), registry.mayBeDemandedBy(adminResolved))
        assertTrue(registry.mayBeDemandedBy(outer).isEmpty())
    }

    @Test
    fun `extends resolver fragments transitively through polymorphic object paths`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = EXTENDED_FRAGMENT_SCHEMA,
                fieldResolvers = { schema ->
                    fun resolver(fragment: Fragment): Resolver.Field =
                        model.testing.fieldResolverOf(fragment) { _, _ -> error("Not invoked") }

                    mapOf(
                        schema.field("Query", "container") to
                            resolver(schema.emptyFragmentOf("Query")),
                        schema.field("Query", "consumer") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      container {
                                        subject {
                                          computed
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.field("User", "display") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on User {
                                      rawUser
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.field("User", "computed") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on User {
                                      display
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.field("Admin", "display") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Admin {
                                      rawAdmin
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.field("Admin", "computed") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Admin {
                                      display
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                    )
                },
            )
        val schema = world.schema
        val predecessorDemand =
            world.executorRegistry
                .resolver(schema.field("Query", "consumer"))
                .predecessorDemand
        val selections = predecessorDemand.subselections.allSelections()
        val user = schema.type("User") as Schema.ObjectType
        val admin = schema.type("Admin") as Schema.ObjectType

        assertEquals(schema.query, predecessorDemand.nominalType)
        assertEquals(
            setOf("computed", "display", "rawUser", "rawAdmin"),
            selections.map { it.key.field.fieldName }.toSet() -
                setOf("container", "subject"),
        )
        assertEquals(
            setOf(user),
            selections.single { it.key.field.fieldName == "rawUser" }.possibleTypes,
        )
        assertEquals(
            setOf(admin),
            selections.single { it.key.field.fieldName == "rawAdmin" }.possibleTypes,
        )
        assertTrue(
            predecessorDemand.subselections.all { selection ->
                selection.key.field.fieldName == "container"
            },
        )
        assertTrue(
            predecessorDemand.subselections.all { container ->
                container.subselections.all { selection ->
                    selection.key.field.fieldName == "subject"
                }
            },
        )
    }

    @Test
    fun `rejects cyclic resolver demand`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = CYCLE_SCHEMA,
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.field("Query", "a") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          b {
                                            value
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                            schema.field("Query", "b") to
                                resolver(
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          a {
                                            value
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                ),
                        )
                    },
                )
            }

        assertTrue(exception.message!!.contains("demand cycle"))
    }

    @Test
    fun `conservatively rejects coordinate cycles broken by error arguments`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          first(arg: Int!): Int!
                          second(arg: Int!): Int!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val parsedSecond =
                            schema
                                .fragmentFrom(
                                    "fragment ignored on Query { second(arg: 1) }",
                                ).subselections
                                .single()
                        val errorSecond =
                            Selection.of(
                                key =
                                    Value.Key.of(
                                        parsedSecond.key.field,
                                        mapOf("arg" to Value.Error),
                                    ),
                                possibleTypes = parsedSecond.possibleTypes,
                                subselections = parsedSecond.subselections,
                            )
                        mapOf(
                            schema.field("Query", "first") to
                                resolver(
                                    Fragment.of(
                                        schema.query,
                                        selectionForestOf(errorSecond),
                                    ),
                                ),
                            schema.field("Query", "second") to
                                resolver(
                                    schema.fragmentFrom(
                                        "fragment ignored on Query { first(arg: 1) }",
                                    ),
                                ),
                        )
                    },
                )
            }

        assertTrue(exception.message!!.contains("demand cycle"))
    }

    private companion object {
        val DEMAND_SCHEMA =
            """
            interface Node {
              id: ID!
              resolved: Result
            }

            type User implements Node {
              id: ID!
              resolved: Result
            }

            type Admin implements Node {
              id: ID!
              resolved: Result
            }

            type Result {
              value: String
            }

            type Query {
              node: Node
              consumer: Result
              outer: Result
            }
            """.trimIndent()

        val CYCLE_SCHEMA =
            """
            type Result {
              value: String
            }

            type Query {
              a: Result
              b: Result
            }
            """.trimIndent()

        val EXTENDED_FRAGMENT_SCHEMA =
            """
            interface Subject {
              computed: String!
            }

            type User implements Subject {
              computed: String!
              display: String!
              rawUser: String!
            }

            type Admin implements Subject {
              computed: String!
              display: String!
              rawAdmin: String!
            }

            type Container {
              subject: Subject!
            }

            type Query {
              container: Container!
              consumer: String!
            }
            """.trimIndent()

        fun resolver(fragment: Fragment): Resolver.Field =
            model.testing.fieldResolverOf(
                objectFragment = fragment,
                function = { _, _ -> error("Not invoked") },
            )

        fun providerContainmentWorld(
            ownerFragment: String,
            providerFragment: String,
        ): TestWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      value: Int!
                    }

                    type Query {
                      result: Int!
                      consume(value: Int!): Int!
                      source(id: Int!): Int!
                      payload: Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "result") to
                            resolver(schema.fragmentFrom(ownerFragment)),
                        schema.field("Query", "consume") to
                            resolver(schema.emptyFragmentOf("Query")),
                        schema.field("Query", "source") to
                            resolver(schema.emptyFragmentOf("Query")),
                        schema.field("Query", "payload") to
                            resolver(schema.emptyFragmentOf("Query")),
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.field("Query", "result") as Schema.ObjectField
                    mapOf(
                        VariableCoordinate.of(owner, Value.Variable.of("value")) to
                            schema.fragmentFrom(providerFragment).subselections.single(),
                    )
                },
            )

    }
}

private fun SelectionForest.allSelections(): List<Selection> {
    val result = mutableListOf<Selection>()
    forEach { selection ->
        result += selection
        result += selection.subselections.allSelections()
    }
    return result
}
