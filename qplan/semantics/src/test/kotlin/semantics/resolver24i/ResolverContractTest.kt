package semantics.resolver24i

import model.EngineResult
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResolverContractTest {
    @Test
    fun `resolves both variable sources while publishing selective output`() {
        val resultFragment =
            """
            fragment Result on Query {
              source
              consume(left: ${'$'}seed, right: ${'$'}path)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Payload {
                      requested: Int!
                      extra: Int!
                    }

                    type Query {
                      result(seed: Int!): Payload!
                      source: Int!
                      consume(left: Int!, right: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val consume = schema.objectField("Query", "consume")
                    val consumeKey =
                        Value.GroundKey.of(
                            consume,
                            mapOf(
                                "left" to 7,
                                "right" to 5,
                            ),
                        )
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val consumed =
                                    input.fieldValues.getValue(consumeKey) as Value.Int
                                schema.objectOf("Payload") {
                                    "requested" setTo consumed.intValue
                                    "extra" setTo 99
                                }
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(5)
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                val left =
                                    arguments.fieldValues.getValue("left") as Value.Int
                                val right =
                                    arguments.fieldValues.getValue("right") as Value.Int
                                Value.Int.of(left.intValue + right.intValue)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                        Value.Variable.of(result, "path") to
                            schema.fromObjectField(resultFragment, listOf("source")),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultField = world.schema.objectField("Query", "result")
        val resultKey = Value.GroundKey.of(resultField, mapOf("seed" to 7))
        val sourceKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "source"),
                emptyMap(),
            )
        val consumeKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "consume"),
                mapOf(
                    "left" to 7,
                    "right" to 5,
                ),
            )
        val selections =
            world.fragmentFrom(
                "fragment QueryResult on Query { result(seed: 7) { requested } }",
            ).subselections

        val resolved =
            resolveSubject(
                world = world,
                root = world.objectOf("Query"),
                selections = selections,
            )

        assertEquals(setOf(resultKey, sourceKey, consumeKey), resolved.keys)
        val payload = assertIs<EngineResult.Object>(resolved.getCell(resultKey).getValue().get())
        assertEquals(setOf("requested"), payload.keys.map { it.field.fieldName }.toSet())
        assertEquals(
            Value.Int.of(12),
            payload.getCell(
                Value.GroundKey.of(
                    world.schema.objectField("Payload", "requested"),
                    emptyMap(),
                ),
            ).getValue().get(),
        )
        assertEquals(
            Value.Int.of(7),
            world.getBinding(Value.Variable.of(resultField, "seed").stamp(listOf(resultKey))),
        )
        assertEquals(
            Value.Int.of(5),
            world.getBinding(Value.Variable.of(resultField, "path").stamp(listOf(resultKey))),
        )
    }
}
