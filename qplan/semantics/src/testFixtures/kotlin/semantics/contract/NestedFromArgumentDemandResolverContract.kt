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

interface NestedFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `retains passive demand below an ungrounded nested resolver key`() {
        val resultFragment =
            """
            fragment Result on Query {
              holder {
                consume(value: ${'$'}argumentValue)
              }
            }
            """.trimIndent()
        val applications = linkedMapOf<String, Int>()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Item {
                      consume(value: Int!): Int!
                      passive: Int!
                    }

                    type Query {
                      holder: Item!
                      result(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val holderKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "holder"),
                            emptyMap(),
                        )
                    val consumeKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "consume"),
                            mapOf("value" to 7),
                        )
                    val passiveKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "passive"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "holder") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                applications.merge("holder", 1, Int::plus)
                                schema.objectOf("Item") {
                                    "passive" setTo 7
                                }
                            },
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                applications.merge("result", 1, Int::plus)
                                val holder = input.fieldValues.getValue(holderKey) as Value.Object
                                holder.fieldValues.getValue(consumeKey)
                            },
                        schema.objectField("Item", "consume") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Consume on Item { passive }",
                                ),
                            ) { input, _ ->
                                applications.merge("consume", 1, Int::plus)
                                input.fieldValues.getValue(passiveKey)
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
                      holder { __typename }
                      result(value: 7)
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(
            Value.Int.of(7),
            resolved
                .getValue(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "result"),
                        mapOf("value" to 7),
                    ),
                ).get(),
        )
        assertEquals(
            mapOf("holder" to 1, "result" to 1, "consume" to 1),
            applications,
        )
    }
}
