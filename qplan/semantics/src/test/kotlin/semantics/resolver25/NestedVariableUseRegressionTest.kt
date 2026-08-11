package semantics.resolver25

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals

class NestedVariableUseRegressionTest {
    @Test
    fun `waits for a provider value before expanding a nested variable use`() {
        val resultFragment =
            """
            fragment Result on Query {
              source
              holder {
                consume(value: ${'$'}value)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Holder {
                      consume(value: Int!): Int!
                    }

                    type Query {
                      result: Int!
                      source: Int!
                      holder: Holder!
                      delay: Int!
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
                            schema.objectField("Holder", "consume"),
                            mapOf("value" to 7),
                        )
                    val delayKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "delay"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val holder =
                                    input.fieldValues.getValue(holderKey) as Value.Object
                                holder.fieldValues.getValue(consumeKey)
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Source on Query { delay }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(delayKey)
                            },
                        schema.objectField("Query", "holder") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Holder")
                            },
                        schema.objectField("Query", "delay") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(7)
                            },
                        schema.objectField("Holder", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Holder")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(
                                resultFragment,
                                listOf("source"),
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "result"),
                emptyMap(),
            )

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(
                    world.fragmentFrom(
                        "fragment ignored on Query { result }",
                    ).subselections,
                )
            }

        assertEquals(Value.Int.of(7), resolved.getValue(resultKey).get())
    }
}
