package semantics.contract

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fromArgument
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Contract for nonempty object fragments with variables bound from resolver arguments.
 */
interface ObjectFragmentFromArgumentResolverContract :
    ResolverContract,
    NestedFromArgumentDemandResolverContract,
    PassiveFromArgumentDemandResolverContract,
    RecursiveListFromArgumentDemandResolverContract {
    @Test
    fun `resolves input selected with a fromArgument variable`() {
        val consumeArguments = mutableListOf<Value.Arguments>()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      result(seed: Int!): Int!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val consume = schema.objectField("Query", "consume")
                    mapOf(
                        schema.field("Query", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { consume(value: ${'$'}seed) }",
                                ),
                            ) { input, arguments ->
                                val seed =
                                    arguments.fieldValues.getValue("seed") as Value.Int
                                val consumeKey =
                                    Value.GroundKey.of(
                                        consume,
                                        mapOf("value" to seed.intValue),
                                    )
                                input.fieldValues.getValue(consumeKey)
                            },
                        consume to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, arguments ->
                                consumeArguments += arguments
                                val value =
                                    arguments.fieldValues.getValue("value") as Value.Int
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
        val variable = Value.Variable.of(resultField, "seed")
        val firstKey =
            Value.GroundKey.of(
                resultField,
                mapOf("seed" to 7),
            )
        val secondKey = Value.GroundKey.of(resultField, mapOf("seed" to 8))
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  first: result(seed: 7)
                  second: result(seed: 8)
                }
                """.trimIndent(),
            )

        val resolved = resolveAndValidate(world, world.objectOf("Query"), fragment)

        assertEquals(Value.Int.of(14), resolved.getValue(firstKey).get())
        assertEquals(Value.Int.of(16), resolved.getValue(secondKey).get())
        assertEquals(
            listOf(
                Value.Arguments.of(
                    world.schema.field("Query", "consume"),
                    mapOf("value" to 7),
                ),
                Value.Arguments.of(
                    world.schema.field("Query", "consume"),
                    mapOf("value" to 8),
                ),
            ),
            consumeArguments,
        )
        val resolver = world.resolverRegistry.resolver(resultField)
        listOf(
            firstKey to Value.Int.of(7),
            secondKey to Value.Int.of(8),
        ).forEach { (groundKey, expectedValue) ->
            val path = listOf(groundKey)
            val selectionStampedVariables =
                resolver
                    .selectionStampedVariableDefinitions(path)
                    .map { definition -> definition.variable }
            val boundVariables =
                selectionStampedVariables
                    .takeIf { variables ->
                        variables.isNotEmpty() && variables.all(world::isBound)
                    } ?: listOf(variable.stamp(path))
            boundVariables.forEach { boundVariable ->
                assertEquals(expectedValue, world.getBinding(boundVariable))
            }
        }
    }

    @Test
    fun `resolves a transitive chain of fromArgument variables`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      one(seed: Int!): Int!
                      two(value: Int!): Int!
                      three(value: Int!): Int!
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
                                    "fragment ignored on Query { two(value: ${'$'}seed) }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(twoKey)
                            },
                        two to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { three(value: ${'$'}value) }",
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
        val oneKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "one"),
                mapOf("seed" to 7),
            )
        val fragment = world.fragmentFrom("fragment ignored on Query { one(seed: 7) }")

        val resolved = resolveAndValidate(world, world.objectOf("Query"), fragment)

        assertEquals(Value.Int.of(8), resolved.getValue(oneKey).get())
    }
}
