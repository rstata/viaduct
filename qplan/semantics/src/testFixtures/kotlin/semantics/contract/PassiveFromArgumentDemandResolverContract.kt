package semantics.contract

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import kotlin.test.Test
import kotlin.test.assertEquals

interface PassiveFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `closes potential demand before descending through a passive object`() {
        val applications = linkedMapOf<String, Int>()
        val resultFragment =
            """
            fragment Result on Query {
              container {
                trigger(value: ${'$'}argumentValue)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Entity {
                      name: String!
                    }

                    type Bridge {
                      id: ID!
                      load: Entity!
                    }

                    type Container {
                      bridge: Bridge!
                      trigger(value: Int!): Int!
                    }

                    type Query {
                      container: Container!
                      result(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val containerKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "container"),
                            emptyMap(),
                        )
                    val triggerKey =
                        Value.GroundKey.of(
                            schema.objectField("Container", "trigger"),
                            mapOf("value" to 7),
                        )
                    mapOf(
                        schema.objectField("Query", "container") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                applications.merge("container", 1, Int::plus)
                                schema.objectOf("Container") {
                                    "bridge" setTo
                                        schema.objectOf("Bridge") {
                                            "id" setTo "entity"
                                        }
                                }
                            },
                        schema.objectField("Bridge", "load") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Load on Bridge { id }",
                                ),
                            ) { _, _ ->
                                applications.merge("load", 1, Int::plus)
                                schema.objectOf("Entity") {
                                    "name" setTo "Ada"
                                }
                            },
                        schema.objectField("Container", "trigger") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment Trigger on Container {
                                      bridge {
                                        load { __typename }
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ ->
                                applications.merge("trigger", 1, Int::plus)
                                Value.Int.of(1)
                            },
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                applications.merge("result", 1, Int::plus)
                                val container =
                                    input.fieldValues.getValue(containerKey) as Value.Object
                                container.fieldValues.getValue(triggerKey)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "argumentValue") to
                            schema.fromArgument(result, "value"),
                    )
                },
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom(
                    """
                    fragment ignored on Query {
                      container {
                        bridge {
                          load { name }
                        }
                      }
                      result(value: 7)
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            Value.Int.of(1),
            resolved.getCell(
                Value.GroundKey.of(
                    world.schema.objectField("Query", "result"),
                    mapOf("value" to 7),
                ),
            ).get(),
        )
        assertEquals(1, applications.getValue("load"))
        assertEquals(1, applications.getValue("trigger"))
    }
}
