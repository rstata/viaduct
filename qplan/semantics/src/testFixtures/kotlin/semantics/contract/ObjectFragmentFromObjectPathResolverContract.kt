package semantics.contract

import model.Value
import model.TypeExpr
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract for resolver variables read from exact paths in the defining object fragment.
 */
interface ObjectFragmentFromObjectPathResolverContract : ResolverContract {
    @Test
    fun `binds a variable from a direct active scalar provider`() {
        val objectFragment =
            """
            fragment Provider on Query {
              source
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      source: Int!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val consume = schema.objectField("Query", "consume")
                    val consumeKey = Value.GroundKey.of(consume, mapOf("value" to 7))
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(objectFragment)) { input, _ ->
                                input.fieldValues.getValue(consumeKey)
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(7)
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                val value =
                                    arguments.fieldValues.getValue("value") as Value.Int
                                Value.Int.of(value.intValue * 2)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(objectFragment, listOf("source")),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultField = world.schema.objectField("Query", "result")
        val resultKey = Value.GroundKey.of(resultField, emptyMap())
        val fragment = world.fragmentFrom("fragment ignored on Query { result }")

        val resolved = resolveAndValidate(world, world.objectOf("Query"), fragment)

        assertEquals(Value.Int.of(14), resolved.fetch(resultKey).value)
        assertEquals(
            Value.Int.of(7),
            world.binding(
                Value.Variable.of(resultField, "value").stamp(listOf(resultKey)),
            ),
        )
    }

    @Test
    fun `binds null and error from nullable provider paths`() {
        listOf<Value.Input?>(null, Value.Error).forEach { provided ->
            val objectFragment =
                """
                fragment Provider on Query {
                  source
                  consume(value: ${'$'}value)
                }
                """.trimIndent()
            val testWorld =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          result: Int
                          source: Int
                          consume(value: Int): Int
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val consume = schema.objectField("Query", "consume")
                        val consumeKey =
                            Value.GroundKey.of(
                                consume,
                                mapOf(
                                    "value" to
                                        when (provided) {
                                            Value.Error -> Value.Error
                                            else -> null
                                        },
                                ),
                            )
                        mapOf(
                            schema.objectField("Query", "result") to
                                fieldResolverOf(schema.fragmentFrom(objectFragment)) { input, _ ->
                                    input.fieldValues.getValue(consumeKey)
                                },
                            schema.objectField("Query", "source") to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                    provided as Value.Output?
                                },
                            consume to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                    arguments.fieldValues.getValue("value") as Value.Output?
                                },
                        )
                    },
                    variableProviders = { schema ->
                        val result = schema.objectField("Query", "result")
                        mapOf(
                            Value.Variable.of(result, "value") to
                                schema.fromObjectField(objectFragment, listOf("source")),
                        )
                    },
                )
            val world = testWorld.assumptions
            val resultField = world.schema.objectField("Query", "result")
            val resultKey = Value.GroundKey.of(resultField, emptyMap())
            val fragment = world.fragmentFrom("fragment ignored on Query { result }")

            val resolved = resolveAndValidate(world, world.objectOf("Query"), fragment)

            assertEquals<Value.Input?>(
                provided,
                resolved.fetch(resultKey).value as Value.Input?,
            )
            assertEquals(
                provided,
                world.binding(
                    Value.Variable.of(resultField, "value").stamp(listOf(resultKey)),
                ),
            )
        }
    }

    @Test
    fun `reads a nested provider after its active ancestor publishes passive content`() {
        val objectFragment =
            """
            fragment Provider on Query {
              box { value }
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Box {
                      value: Int!
                    }

                    type Query {
                      result: Int!
                      box: Box!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val consume = schema.objectField("Query", "consume")
                    val consumeKey = Value.GroundKey.of(consume, mapOf("value" to 9))
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(objectFragment)) { input, _ ->
                                input.fieldValues.getValue(consumeKey)
                            },
                        schema.objectField("Query", "box") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Box") {
                                    "value" setTo 9
                                }
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(
                                objectFragment,
                                listOf("box", "value"),
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
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom("fragment ignored on Query { result }"),
            )

        assertEquals(Value.Int.of(9), resolved.fetch(resultKey).value)
    }

    @Test
    fun `converts a terminal scalar list to a ground input list`() {
        val objectFragment =
            """
            fragment Provider on Query {
              source
              consume(values: ${'$'}values)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      source: [Int!]!
                      consume(values: [Int!]!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.objectField("Query", "source")
                    val consume = schema.objectField("Query", "consume")
                    val consumeKey =
                        Value.GroundKey.of(
                            consume,
                            mapOf("values" to listOf(2, 3, 5)),
                        )
                    val elementType =
                        (source.typeExpr as TypeExpr.List).elementType
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(objectFragment)) { input, _ ->
                                input.fieldValues.getValue(consumeKey)
                            },
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.OutputList.of(
                                    elementType,
                                    listOf(
                                        Value.Int.of(2),
                                        Value.Int.of(3),
                                        Value.Int.of(5),
                                    ),
                                )
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                val values =
                                    arguments.fieldValues.getValue("values") as Value.InputList
                                Value.Int.of(
                                    values.values.sumOf { (it as Value.Int).intValue },
                                )
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "values") to
                            schema.fromObjectField(objectFragment, listOf("source")),
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
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom("fragment ignored on Query { result }"),
            )

        assertEquals(Value.Int.of(10), resolved.fetch(resultKey).value)
    }
}
