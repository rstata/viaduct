package semantics.contract

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import model.testing.nodeResolverOf
import kotlin.test.Test
import kotlin.test.assertEquals

interface ObjectPathNodeInteractionResolverContract : ResolverContract {
    @Test
    fun `retains potential demand through a passive node bridge`() {
        var driverApplications = 0
        val driverFragment =
            """
            fragment Driver on Query {
              source
              item {
                trigger(value: ${'$'}sourceValue)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    interface Node {
                      id: ID!
                    }

                    type Details {
                      related: Profile!
                    }

                    type Profile implements Node {
                      id: ID!
                      details: Details!
                    }

                    type Item {
                      profile: Profile!
                      trigger(value: Int!): Int!
                    }

                    type Query {
                      item: Item!
                      source: Int!
                      driver: Profile!
                    }
                    """.trimIndent(),
                nodeResolvers = { schema ->
                    mapOf(
                        (schema.type("Profile") as model.Schema.ObjectType) to
                            nodeResolverOf { id ->
                                schema.objectOf("Profile") {
                                    "id" setTo id
                                    "details" setTo
                                        schema.objectOf("Details") {
                                            "related" setTo
                                                schema.objectOf("Profile") {
                                                    "id" setTo "related"
                                                }
                                        }
                                }
                            },
                    )
                },
                fieldResolvers = { schema ->
                    mapOf(
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") {
                                    "profile" setTo
                                        schema.objectOf("Profile") {
                                            "id" setTo "profile"
                                        }
                                }
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(7)
                            },
                        schema.objectField("Query", "driver") to
                            fieldResolverOf(schema.fragmentFrom(driverFragment)) { _, _ ->
                                driverApplications += 1
                                schema.objectOf("Profile") {
                                    "id" setTo "driver"
                                }
                            },
                        schema.objectField("Item", "trigger") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment Trigger on Item {
                                      profile {
                                        details {
                                          related { __typename }
                                        }
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ ->
                                Value.Int.of(7)
                            },
                    )
                },
                variableProviders = { schema ->
                    val driver = schema.objectField("Query", "driver")
                    mapOf(
                        Value.Variable.of(driver, "sourceValue") to
                            schema.fromObjectField(driverFragment, listOf("source")),
                    )
                },
            )
        val world = testWorld.assumptions

        resolveAndValidate(
            world,
            world.objectOf("Query"),
            world.fragmentFrom(
                """
                    fragment ignored on Query {
                      item {
                        profile {
                          details { __typename }
                        }
                      }
                      driver { id }
                    }
                """.trimIndent(),
            ),
        )

        assertEquals(1, driverApplications)
    }
}
