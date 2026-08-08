package semantics

import model.Assumptions
import model.EngineResult
import model.SelectionForest
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fromArgument
import semantics.resolver02.resolve as resolve02
import semantics.resolver03.resolve as resolve03
import kotlin.test.Test
import kotlin.test.assertEquals

class FromArgumentResolutionTest {
    @Test
    fun `resolver02 resolves input selected with a fromArgument variable`() {
        assertFromArgumentResolution { world, root, selections ->
            context(world) {
                root.resolve02(selections)
            }
        }
    }

    @Test
    fun `resolver03 resolves input selected with a fromArgument variable`() {
        assertFromArgumentResolution { world, root, selections ->
            context(world) {
                root.resolve03(selections)
            }
        }
    }

    @Test
    fun `resolver02 resolves a transitive chain of fromArgument variables`() {
        assertTransitiveFromArgumentResolution { world, root, selections ->
            context(world) {
                root.resolve02(selections)
            }
        }
    }

    @Test
    fun `resolver03 resolves a transitive chain of fromArgument variables`() {
        assertTransitiveFromArgumentResolution { world, root, selections ->
            context(world) {
                root.resolve03(selections)
            }
        }
    }

    private fun assertTransitiveFromArgumentResolution(
        resolve:
            (
                Assumptions,
                Value.Object,
                SelectionForest,
            ) -> EngineResult.Object,
    ) {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      one(seed: Int!): Int!     # { two (${'$'}seed) } return it.two
                      two(value: Int!): Int!    # { three(${'$'}value) } return it.three
                      three(value: Int!): Int!  # { } return ${'$'}value+1
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val one = schema.objectField("Query", "one")
                    val two = schema.objectField("Query", "two")
                    val three = schema.objectField("Query", "three")
                    val twoKey = Value.GroundKey.of(two, mapOf("value" to 7))
                    val threeKey = Value.GroundKey.of(three, mapOf("value" to 7))
                    mapOf(
                        one to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      two(value: ${'$'}seed)
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(twoKey)
                            },
                        two to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      three(value: ${'$'}value)
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(threeKey)
                            },
                        three to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, arguments ->
                                val value =
                                    arguments.fieldValues.getValue("value") as Value.Int
                                Value.Int.of(value.intValue + 1)
                            },
                    )
                },
                variableProviders = { schema ->
                    val one = schema.objectField("Query", "one")
                    val two = schema.objectField("Query", "two")
                    mapOf(
                        Value.Variable.of(one, "seed") to
                            schema.fromArgument(one, "seed"),
                        Value.Variable.of(two, "value") to
                            schema.fromArgument(two, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val one = world.schema.objectField("Query", "one")
        val oneKey = Value.GroundKey.of(one, mapOf("seed" to 7))
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { one(seed: 7) }",
            )

        val resolved =
            resolve(
                world,
                world.objectOf("Query"),
                fragment.subselections,
            )

        assertEquals(Value.Int.of(8), resolved.fetch(oneKey).value)
    }

    private fun assertFromArgumentResolution(
        resolve:
            (
                Assumptions,
                Value.Object,
                SelectionForest,
            ) -> EngineResult.Object,
    ) {
        val consumeArguments = mutableListOf<Value.Arguments>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result(seed: Int!): Int!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val consume = schema.objectField("Query", "consume")
                    val consumeKey = Value.GroundKey.of(consume, mapOf("value" to 7))
                    mapOf(
                        schema.field("Query", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      consume(value: ${'$'}seed)
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(consumeKey)
                            },
                        consume to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, arguments ->
                                consumeArguments += arguments
                                val value = arguments.fieldValues.getValue("value") as Value.Int
                                Value.Int.of(value.intValue * 2)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultField = world.schema.objectField("Query", "result")
        val resultKey = Value.GroundKey.of(resultField, mapOf("seed" to 7))
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { result(seed: 7) }",
            )

        val resolved =
            resolve(
                world,
                world.objectOf("Query"),
                fragment.subselections,
            )

        assertEquals(Value.Int.of(14), resolved.fetch(resultKey).value)
        assertEquals(
            listOf(Value.Arguments.of(world.schema.field("Query", "consume"), mapOf("value" to 7))),
            consumeArguments,
        )
    }
}
