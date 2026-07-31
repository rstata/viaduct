package model.registry

import model.Fragment
import model.Schema
import model.emptyFragmentOf
import model.fragmentFrom
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResolverDemandTest {
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

        fun resolver(fragment: Fragment): Resolver.Field =
            model.testing.fieldResolverOf(
                objectFragment = fragment,
                function = { _, _ -> error("Not invoked") },
            )

    }
}
