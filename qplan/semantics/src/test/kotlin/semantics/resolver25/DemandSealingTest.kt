package semantics.resolver25

import model.EngineResult
import model.Schema
import model.SelectionForest
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.instantiateBindings
import model.merge
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DemandSealingTest {
    @Test
    fun `binds a direct scalar sibling before grounding its consumer key`() {
        val resultFragment =
            """
            fragment Result on Query {
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
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
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
                            schema.fromObjectField(resultFragment, listOf("source")),
                    )
                },
            )
        val resultKey =
            Value.GroundKey.of(
                testWorld.schema.objectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveResult(testWorld)

        assertEquals(Value.Int.of(14), resolved.getValue(resultKey).get())
        assertEquals(
            Value.Int.of(7),
            testWorld.assumptions.getBinding(
                Value.Variable
                    .of(resultKey.field, "value")
                    .stamp(listOf(resultKey)),
            ),
        )
    }

    @Test
    fun `preparing a grounded consumer contributes its demand before the producer launches`() {
        var producerApplications = 0
        var producerDemand: SelectionForest? = null
        val resultFragment =
            """
            fragment Result on Query {
              a { early }
              b
              c(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      early: Int!
                      late: Int!
                    }

                    type Query {
                      result: Int!
                      a: Payload!
                      b: Int!
                      c(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val aKey = Value.GroundKey.of(schema.objectField("Query", "a"), emptyMap())
                    val cKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "c"),
                            mapOf("value" to 4),
                        )
                    val lateKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "late"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.fieldValues.getValue(cKey)
                            },
                        schema.objectField("Query", "a") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "early" setTo 1
                                    "late" setTo 9
                                }
                            }.observeApplications { _, _, demand ->
                                producerApplications += 1
                                producerDemand = demand
                            },
                        schema.objectField("Query", "b") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(4)
                            },
                        schema.objectField("Query", "c") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment C on Query { a { late } }",
                                ),
                            ) { input, _ ->
                                val payload = input.fieldValues.getValue(aKey) as Value.Object
                                payload.fieldValues.getValue(lateKey)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(resultFragment, listOf("b")),
                    )
                },
            )

        val resolved = resolveResult(testWorld)
        val payloadType = testWorld.schema.type("Payload") as Schema.ObjectType

        assertEquals(1, producerApplications)
        assertEquals(
            setOf("early", "late"),
            context(testWorld.assumptions) {
                requireNotNull(producerDemand)
                    .merge(payloadType)
                    .instantiateBindings()
                    .groundKeys()
                    .mapTo(linkedSetOf()) { key -> key.field.fieldName }
            },
        )
        assertEquals(
            Value.Int.of(9),
            resolved
                .getValue(
                    Value.GroundKey.of(
                        testWorld.schema.objectField("Query", "result"),
                        emptyMap(),
                    ),
                ).get(),
        )
    }

    @Test
    fun `late equality merges disjoint selections before one application`() {
        var producerApplications = 0
        var producerDemand: SelectionForest? = null
        val resultFragment =
            """
            fragment Result on Query {
              b
              exact: a(name: "same") { one }
              symbolic: a(name: ${'$'}name) { two }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      one: Int!
                      two: Int!
                    }

                    type Query {
                      result: Int!
                      a(name: String!): Payload!
                      b: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val aKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "a"),
                            mapOf("name" to "same"),
                        )
                    val oneKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "one"),
                            emptyMap(),
                        )
                    val twoKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "two"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val payload = input.fieldValues.getValue(aKey) as Value.Object
                                val one = payload.fieldValues.getValue(oneKey) as Value.Int
                                val two = payload.fieldValues.getValue(twoKey) as Value.Int
                                Value.Int.of(one.intValue + two.intValue)
                            },
                        schema.objectField("Query", "a") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "one" setTo 3
                                    "two" setTo 5
                                }
                            }.observeApplications { _, _, demand ->
                                producerApplications += 1
                                producerDemand = demand
                            },
                        schema.objectField("Query", "b") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.String.of("same")
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "name") to
                            schema.fromObjectField(resultFragment, listOf("b")),
                    )
                },
            )

        val resolved = resolveResult(testWorld)
        val payloadType = testWorld.schema.type("Payload") as Schema.ObjectType

        assertEquals(1, producerApplications)
        assertEquals(
            setOf("one", "two"),
            context(testWorld.assumptions) {
                requireNotNull(producerDemand)
                    .merge(payloadType)
                    .instantiateBindings()
                    .groundKeys()
                    .mapTo(linkedSetOf()) { key -> key.field.fieldName }
            },
        )
        assertTrue(
            resolved
                .getValue(
                    Value.GroundKey.of(
                        testWorld.schema.objectField("Query", "result"),
                        emptyMap(),
                    ),
                ).get() == Value.Int.of(8),
        )
    }

    @Test
    fun `binds a nested path variable at a resolver-backed terminal field`() {
        val resultFragment =
            """
            fragment Result on Query {
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
                    val consumeKey = Value.GroundKey.of(consume, mapOf("value" to 11))
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.fieldValues.getValue(consumeKey)
                            },
                        schema.objectField("Query", "box") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Box")
                            },
                        schema.objectField("Box", "value") to
                            fieldResolverOf(schema.emptyFragmentOf("Box")) { _, _ ->
                                Value.Int.of(11)
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
                                resultFragment,
                                listOf("box", "value"),
                            ),
                    )
                },
            )

        val resolved = resolveResult(testWorld)
        val resultKey =
            Value.GroundKey.of(
                testWorld.schema.objectField("Query", "result"),
                emptyMap(),
            )

        assertEquals(Value.Int.of(11), resolved.getValue(resultKey).get())
        assertEquals(
            Value.Int.of(11),
            testWorld.assumptions.getBinding(
                Value.Variable
                    .of(resultKey.field, "value")
                    .stamp(listOf(resultKey)),
            ),
        )
    }

    @Test
    fun `rejects a cycle between provider completion and consumer preparation`() {
        val resultFragment =
            """
            fragment Result on Query {
              b
              c(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      a: Int!
                      b: Int!
                      c(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val aKey = Value.GroundKey.of(schema.objectField("Query", "a"), emptyMap())
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { _, _ ->
                                Value.Int.of(0)
                            },
                        schema.objectField("Query", "a") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                        schema.objectField("Query", "b") to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment B on Query { a }"),
                            ) { input, _ ->
                                input.fieldValues.getValue(aKey)
                            },
                        schema.objectField("Query", "c") to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment C on Query { a }"),
                            ) { input, _ ->
                                input.fieldValues.getValue(aKey)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(resultFragment, listOf("b")),
                    )
                },
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                resolveResult(testWorld)
            }

        assertTrue(error.message.orEmpty().contains("one-shot phase order"))
        assertTrue(error.message.orEmpty().contains("cycle"))
    }

    private fun resolveResult(testWorld: TestWorld): EngineResult.Object {
        val world = testWorld.assumptions
        return context(world) {
            world.objectOf("Query").resolve(
                world.fragmentFrom(
                    "fragment ignored on Query { result }",
                ).subselections,
            )
        }
    }
}
