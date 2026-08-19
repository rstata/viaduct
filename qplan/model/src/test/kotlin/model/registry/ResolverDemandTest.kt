package model.registry

import model.ObjectEngineResult
import model.ArgumentResolutionError

import model.Fragment
import model.Schema
import model.Selection
import model.SelectionForest
import model.Value
import model.emptyFragmentOf
import model.fieldExpressions
import model.fragmentFrom
import model.selectionForestOf
import model.testing.FieldResolverDefinition
import model.testing.TestWorld
import model.testing.fromArgument
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
                    val owner = schema.objectField("Query", "x")
                    mapOf(
                        Value.Variable.of(owner, "b") to
                            schema.fromObjectField(
                                """
                                fragment ignored on Query {
                                  z(c: ${'$'}c)
                                }
                                """.trimIndent(),
                                listOf("z"),
                            ),
                        Value.Variable.of(owner, "c") to
                            schema.fromObjectField(
                                """
                                fragment ignored on Query {
                                  raw
                                }
                                """.trimIndent(),
                                listOf("raw"),
                            ),
                    )
                },
            )
        val schema = world.schema
        val registry = world.resolverRegistry
        val x = schema.objectField("Query", "x")
        val y = schema.objectField("Query", "y")
        val z = schema.objectField("Query", "z")
        val raw = schema.objectField("Query", "raw")

        assertEquals(setOf(y, z, raw), registry.mayDemandFrom(x))
    }

    @Test
    fun `variable names are local to their defining field resolver`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      x: Int
                      y: Int
                      xSource: Int
                      ySource: Int
                      consume(value: Int): Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.emptyFragmentOf("Query")
                    mapOf(
                        schema.field("Query", "x") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      xSource
                                      consume(value: ${'$'}same)
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.field("Query", "y") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      ySource
                                      consume(value: ${'$'}same)
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.field("Query", "xSource") to resolver(empty),
                        schema.field("Query", "ySource") to resolver(empty),
                        schema.field("Query", "consume") to resolver(empty),
                    )
                },
                variableProviders = { schema ->
                    val x = schema.objectField("Query", "x")
                    val y = schema.objectField("Query", "y")
                    mapOf(
                        Value.Variable.of(x, "same") to
                            schema.fromObjectField(
                                "fragment ignored on Query { xSource }",
                                listOf("xSource"),
                            ),
                        Value.Variable.of(y, "same") to
                            schema.fromObjectField(
                                "fragment ignored on Query { ySource }",
                                listOf("ySource"),
                            ),
                    )
                },
            )
        val schema = world.schema
        val x = schema.objectField("Query", "x")
        val y = schema.objectField("Query", "y")
        val xVariable = Value.Variable.of(x, "same")
        val yVariable = Value.Variable.of(y, "same")

        assertEquals(
            setOf(xVariable),
            world.resolverRegistry.resolver(x).variables.keys,
        )
        assertEquals(
            setOf(yVariable),
            world.resolverRegistry.resolver(y).variables.keys,
        )
        assertEquals(
            schema.objectField("Query", "xSource"),
            assertIs<VariableDefinition.FromObjectField>(
                world.resolverRegistry.resolver(x).variables.getValue(xVariable),
            ).path.single().field,
        )
        assertEquals(
            schema.objectField("Query", "ySource"),
            assertIs<VariableDefinition.FromObjectField>(
                world.resolverRegistry.resolver(y).variables.getValue(yVariable),
            ).path.single().field,
        )
    }

    @Test
    fun `defines a variable from an argument without adding provider demand`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      source(seed: Int!): Int
                      consume(value: Int): Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "source") to
                            resolver(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      consume(value: ${'$'}seed)
                                    }
                                    """.trimIndent(),
                                ),
                            ),
                        schema.field("Query", "consume") to
                            resolver(schema.emptyFragmentOf("Query")),
                    )
                },
                variableProviders = { schema ->
                    val source = schema.objectField("Query", "source")
                    mapOf(
                        Value.Variable.of(source, "seed") to
                            schema.fromArgument(source, "seed"),
                    )
                },
            )
        val source = world.schema.objectField("Query", "source")
        val consume = world.schema.objectField("Query", "consume")
        val variable = Value.Variable.of(source, "seed")

        val definition =
            assertIs<VariableDefinition.FromArgument>(
                world.resolverRegistry.resolver(source).variables.getValue(variable),
            )
        assertEquals(source.arguments.fields.getValue("seed"), definition.argument)
        assertEquals(setOf(consume), world.resolverRegistry.mayDemandFrom(source))

        val resolver = world.resolverRegistry.resolver(source)
        val objectFragment = resolver.objectFragment.single()

        assertEquals(
            variable,
            objectFragment.key.arguments.fieldExpressions().getValue("value"),
        )
    }

    @Test
    fun `rejects an argument from a different resolver field`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          source(seed: Int!): Int
                          other(seed: Int!): Int
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val empty = schema.emptyFragmentOf("Query")
                        mapOf(
                            schema.field("Query", "source") to resolver(empty),
                            schema.field("Query", "other") to resolver(empty),
                        )
                    },
                    variableProviders = { schema ->
                        val source = schema.objectField("Query", "source")
                        val other = schema.objectField("Query", "other")
                        mapOf(
                            Value.Variable.of(source, "seed") to
                                schema.fromArgument(other, "seed"),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("does not belong to Query/source"))
    }

    @Test
    fun `rejects variable cycles`() {
        val schemaSDL =
            """
            type Query {
              x: Int
              y: Int
              z(a: Int, b: Int): Int
            }
            """.trimIndent()
        val cycle =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                    schemaSDL = schemaSDL,
                    fieldResolvers = { schema ->
                        val resolvers =
                            mutableMapOf<Schema.OutputField, FieldResolverDefinition>()
                        schema.query.fields.values
                            .filter { it.fieldName != "__typename" }
                            .forEach { field ->
                                resolvers[field] = resolver(schema.emptyFragmentOf("Query"))
                            }
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
                        val owner = schema.objectField("Query", "x")
                        mapOf(
                            Value.Variable.of(owner, "a") to
                                schema.fromObjectField(
                                    "fragment ignored on Query { z(a: 0, b: ${'$'}b) }",
                                    listOf("z"),
                                ),
                            Value.Variable.of(owner, "b") to
                                schema.fromObjectField(
                                    "fragment ignored on Query { z(a: ${'$'}a, b: 0) }",
                                    listOf("z"),
                                ),
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
                    providerResponsePath = listOf("source"),
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
                    providerResponsePath = listOf("value"),
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
                    providerResponsePath = listOf("source"),
                )
            }
        assertTrue(argumentDistinct.message!!.contains("not contained"))
    }

    @Test
    fun `rejects a provider path behind a narrowing guard`() {
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
                            Value.Variable.of(owner, "value") to
                                schema.fromObjectField(
                                    """
                                    fragment ignored on Query {
                                      subject {
                                        ... on Second {
                                          value
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                    listOf("subject", "value"),
                                ),
                        )
                    },
                )
            }

        assertTrue(failure.message!!.contains("lossy type condition Subject to Second"))
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
                        schema.field("Query", "node_V_A_node") to
                            resolver(schema.emptyFragmentOf("Query")),
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
        val registry = world.resolverRegistry
        val user = schema.type("User") as Schema.ObjectType
        val admin = schema.type("Admin") as Schema.ObjectType
        val queryNodeBridge = schema.objectField("Query", "node_V_A_node")
        val nodePayload = schema.objectField("Node_V_A_Bridge", "node")
        val consumer = schema.objectField("Query", "consumer")
        val outer = schema.objectField("Query", "outer")
        val userResolved = schema.objectField("User", "resolved")
        val adminResolved = schema.objectField("Admin", "resolved")

        assertEquals(
            setOf(queryNodeBridge, nodePayload, userResolved, adminResolved),
            registry.mayDemandFrom(consumer),
        )
        assertEquals(setOf(consumer), registry.mayDemandFrom(outer))
        assertTrue(registry.mayDemandFrom(queryNodeBridge).isEmpty())
        assertTrue(registry.mayDemandFrom(nodePayload).isEmpty())
        assertTrue(registry.mayDemandFrom(userResolved).isEmpty())
        assertTrue(registry.mayDemandFrom(adminResolved).isEmpty())

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
                                    ObjectEngineResult.Key.of(
                                        parsedSecond.key.field,
                                        mapOf("arg" to ArgumentResolutionError),
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

        fun resolver(fragment: Fragment): FieldResolverDefinition =
            model.testing.fieldResolverOf(
                objectFragment = fragment,
                function = { _, _ -> error("Not invoked") },
            )

        fun providerContainmentWorld(
            ownerFragment: String,
            providerFragment: String,
            providerResponsePath: List<String>,
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
                        Value.Variable.of(owner, "value") to
                            schema.fromObjectField(
                                providerFragment,
                                providerResponsePath,
                            ),
                    )
                },
            )

    }
}
