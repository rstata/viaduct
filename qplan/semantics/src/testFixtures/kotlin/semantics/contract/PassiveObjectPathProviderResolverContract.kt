package semantics.contract

import model.EngineResult
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals

interface PassiveObjectPathProviderResolverContract : ResolverContract {
    @Test
    fun `installs a resolver promise below a passive provider field`() {
        val resultFragment =
            """
            fragment Result on Item {
              provider { value }
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Provider {
                      value: Int!
                    }

                    type Item {
                      result: Int!
                      provider: Provider!
                      consume(value: Int!): Int!
                    }

                    type Query {
                      item: Item!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val itemKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "item"),
                            emptyMap(),
                        )
                    val resultKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "result"),
                            emptyMap(),
                        )
                    val consumeKey =
                        Value.GroundKey.of(
                            schema.objectField("Item", "consume"),
                            mapOf("value" to 11),
                        )
                    mapOf(
                        schema.objectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") {
                                    "provider" setTo schema.objectOf("Provider")
                                }
                            },
                        schema.objectField("Item", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.fieldValues.getValue(consumeKey)
                            },
                        schema.objectField("Item", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                        schema.objectField("Provider", "value") to
                            fieldResolverOf(schema.emptyFragmentOf("Provider")) { _, _ ->
                                Value.Int.of(11)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Item", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(
                                resultFragment,
                                listOf("provider", "value"),
                            ),
                    )
                },
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom(
                    "fragment ignored on Query { item { result } }",
                ),
            )
        val item =
            resolved.getValue(
                Value.GroundKey.of(
                    world.schema.objectField("Query", "item"),
                    emptyMap(),
                ),
            ).get() as EngineResult.Object

        assertEquals(
            Value.Int.of(11),
            item.getValue(
                Value.GroundKey.of(
                    world.schema.objectField("Item", "result"),
                    emptyMap(),
                ),
            ).get(),
        )
    }
}
