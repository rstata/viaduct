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
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.resolver25StructuralSignatures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

        val observation = observeResult(testWorld)
        val resolved = observation.result
        val payloadType = testWorld.schema.type("Payload") as Schema.ObjectType
        val signatures = observation.lifecycleEvents.resolver25StructuralSignatures()

        assertEquals(1, producerApplications)
        assertContains(
            signatures,
            Resolver25StructuralSignature.PRELAUNCH_DEMAND_AGGREGATION,
        )
        assertContains(
            signatures,
            Resolver25StructuralSignature.OBJECT_PATH_KEY_GROUNDING,
        )
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

        val observation = observeResult(testWorld)
        val resolved = observation.result
        val payloadType = testWorld.schema.type("Payload") as Schema.ObjectType

        assertEquals(1, producerApplications)
        assertContains(
            observation.lifecycleEvents.resolver25StructuralSignatures(),
            Resolver25StructuralSignature.LITERAL_VARIABLE_KEY_CONVERGENCE,
        )
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
    fun `binds a three-component path variable at a resolver-backed terminal field`() {
        val resultFragment =
            """
            fragment Result on Query {
              box { nested { value } }
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Box {
                      nested: Nested!
                    }

                    type Nested {
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
                                schema.objectOf("Box") {
                                    "nested" setTo schema.objectOf("Nested")
                                }
                            },
                        schema.objectField("Nested", "value") to
                            fieldResolverOf(schema.emptyFragmentOf("Nested")) { _, _ ->
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
                                listOf("box", "nested", "value"),
                            ),
                    )
                },
            )

        val observation = observeResult(testWorld)
        val resolved = observation.result
        val resultKey =
            Value.GroundKey.of(
                testWorld.schema.objectField("Query", "result"),
                emptyMap(),
            )

        assertEquals(Value.Int.of(11), resolved.getValue(resultKey).get())
        assertContains(
            observation.lifecycleEvents.resolver25StructuralSignatures(),
            Resolver25StructuralSignature.NESTED_PROVIDER_PATH,
        )
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
    fun `structural demand closes transitively before a nested provider can complete`() {
        var consumerApplications = 0
        val consumerArguments = linkedSetOf<Int>()
        var producerApplications = 0
        var producerDemand: SelectionForest? = null
        var transitiveProducerApplications = 0
        var transitiveProducerDemand: SelectionForest? = null
        val resultFragment =
            """
            fragment Result on Query {
              b { value }
              c(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Box {
                      value: Int!
                    }

                    type Payload {
                      early: Int!
                      late: Int!
                    }

                    type Seeds {
                      early: Int!
                      late: Int!
                    }

                    type Query {
                      result: Int!
                      a: Payload!
                      b: Box!
                      c(value: Int!): Int!
                      z: Seeds!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val aKey = Value.GroundKey.of(schema.objectField("Query", "a"), emptyMap())
                    val zKey = Value.GroundKey.of(schema.objectField("Query", "z"), emptyMap())
                    val earlyKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "early"),
                            emptyMap(),
                        )
                    val lateKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "late"),
                            emptyMap(),
                        )
                    val seedEarlyKey =
                        Value.GroundKey.of(
                            schema.objectField("Seeds", "early"),
                            emptyMap(),
                        )
                    val seedLateKey =
                        Value.GroundKey.of(
                            schema.objectField("Seeds", "late"),
                            emptyMap(),
                        )
                    val cKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "c"),
                            mapOf("value" to 4),
                        )
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.fieldValues.getValue(cKey)
                            },
                        schema.objectField("Query", "a") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment A on Query { z { early late } }",
                                ),
                            ) { input, _ ->
                                val seeds = input.fieldValues.getValue(zKey) as Value.Object
                                schema.objectOf("Payload") {
                                    "early" setTo seeds.fieldValues.getValue(seedEarlyKey)
                                    "late" setTo seeds.fieldValues.getValue(seedLateKey)
                                }
                            }.observeApplications { _, _, demand ->
                                producerApplications += 1
                                producerDemand = demand
                            },
                        schema.objectField("Query", "b") to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment B on Query { a { early } }"),
                            ) { input, _ ->
                                val payload = input.fieldValues.getValue(aKey) as Value.Object
                                val value = payload.fieldValues.getValue(earlyKey)
                                schema.objectOf("Box") {
                                    "value" setTo value
                                }
                            },
                        schema.objectField("Query", "c") to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment C on Query { a { late } }"),
                            ) { input, arguments ->
                                consumerApplications += 1
                                val value: Value.Int =
                                    arguments.fieldValues.getValue("value") as Value.Int
                                consumerArguments += value.intValue
                                val payload = input.fieldValues.getValue(aKey) as Value.Object
                                payload.fieldValues.getValue(lateKey)
                            },
                        schema.objectField("Query", "z") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Seeds") {
                                    "early" setTo 4
                                    "late" setTo 9
                                }
                            }.observeApplications { _, _, demand ->
                                transitiveProducerApplications += 1
                                transitiveProducerDemand = demand
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(
                                resultFragment,
                                listOf("b", "value"),
                            ),
                    )
                },
            )

        val resolved: EngineResult.Object =
            context(testWorld.assumptions) {
                testWorld.assumptions
                    .objectOf("Query")
                    .resolve(
                        testWorld.assumptions
                            .fragmentFrom(
                                "fragment ignored on Query { result c(value: 7) }",
                            ).subselections,
                    )
            }
        val payloadType = testWorld.schema.type("Payload") as Schema.ObjectType
        val seedsType = testWorld.schema.type("Seeds") as Schema.ObjectType

        assertEquals(2, consumerApplications)
        assertEquals(setOf(4, 7), consumerArguments)
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
        assertEquals(1, transitiveProducerApplications)
        assertEquals(
            setOf("early", "late"),
            context(testWorld.assumptions) {
                requireNotNull(transitiveProducerDemand)
                    .merge(seedsType)
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

    private fun resolveResult(testWorld: TestWorld): EngineResult.Object {
        return observeResult(testWorld).result
    }

    private fun observeResult(testWorld: TestWorld): Resolver25ResolutionObservation {
        val world = testWorld.assumptions
        return observeWithLifecycleValidation(
            world = world,
            root = world.objectOf("Query"),
            selections =
                world.fragmentFrom(
                    "fragment ignored on Query { result }",
                ).subselections,
        )
    }
}
