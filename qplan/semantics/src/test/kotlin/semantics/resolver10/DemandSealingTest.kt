package semantics.resolver10

import model.Assumptions
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
import model.testing.fromArgument
import model.testing.fromObjectField
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemandSealingTest {
    @Test
    fun `symbolic nested demand does not block another occurrence of the same field`() {
        val branchLaunches = mutableListOf<Int>()
        val triggerFragment =
            """
            fragment Trigger on Query {
              consumer(value: ${'$'}value)
              provider
            }
            """.trimIndent()
        val consumerFragment =
            """
            fragment Consumer on Query {
              branch(id: 1) {
                keyed(value: ${'$'}consumerValue)
              }
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      value: Int!
                      keyed(value: String!): Int!
                    }

                    type Query {
                      trigger: Int!
                      provider: String!
                      consumer(value: String!): Int!
                      branch(id: Int!): Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val consumerKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "consumer"),
                            mapOf("value" to "bound"),
                        )
                    val providerBranchKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "branch"),
                            mapOf("id" to 2),
                        )
                    val consumerBranchKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "branch"),
                            mapOf("id" to 1),
                        )
                    val valueKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "value"),
                            emptyMap(),
                        )
                    val keyedKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "keyed"),
                            mapOf("value" to "bound"),
                        )
                    mapOf(
                        schema.objectField("Query", "trigger") to
                            fieldResolverOf(schema.fragmentFrom(triggerFragment)) { input, _ ->
                                input.fieldValues.getValue(consumerKey)
                            },
                        schema.objectField("Query", "provider") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Provider on Query { branch(id: 2) { value } }",
                                ),
                            ) { input, _ ->
                                val payload =
                                    input.fieldValues.getValue(providerBranchKey) as Value.Object
                                check(payload.fieldValues.getValue(valueKey) == Value.Int.of(7))
                                Value.String.of("bound")
                            },
                        schema.objectField("Query", "consumer") to
                            fieldResolverOf(schema.fragmentFrom(consumerFragment)) { input, _ ->
                                val payload =
                                    input.fieldValues.getValue(consumerBranchKey) as Value.Object
                                payload.fieldValues.getValue(keyedKey)
                            },
                        schema.objectField("Query", "branch") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "value" setTo 7
                                    field("keyed", "value" to "bound") setTo 11
                                }
                            }.observeApplications { _, arguments, _ ->
                                branchLaunches +=
                                    (arguments.fieldValues.getValue("id") as Value.Int).intValue
                            },
                    )
                },
                variableProviders = { schema ->
                    val trigger = schema.objectField("Query", "trigger")
                    val consumer = schema.objectField("Query", "consumer")
                    mapOf(
                        Value.Variable.of(trigger, "value") to
                            schema.fromObjectField(triggerFragment, listOf("provider")),
                        Value.Variable.of(consumer, "consumerValue") to
                            schema.fromArgument(consumer, "value"),
                    )
                },
            )

        val result = resolveTrigger(testWorld)

        assertEquals(listOf(2, 1), branchLaunches)
        assertEquals(
            Value.Int.of(11),
            result.getValue(
                Value.GroundKey.of(
                    testWorld.schema.objectField("Query", "trigger"),
                    emptyMap(),
                ),
            ).get(),
        )
    }

    @Test
    fun `late resolver expansion contributes demand before an independent producer launches`() {
        var producerDemand: SelectionForest? = null
        val triggerFragment =
            """
            fragment Trigger on Query {
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
                      trigger: Int!
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
                        schema.objectField("Query", "trigger") to
                            fieldResolverOf(schema.fragmentFrom(triggerFragment)) { input, _ ->
                                input.fieldValues.getValue(cKey)
                            },
                        schema.objectField("Query", "a") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "early" setTo 1
                                    "late" setTo 9
                                }
                            }.observeApplications { _, _, demand ->
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
                    val trigger = schema.objectField("Query", "trigger")
                    mapOf(
                        Value.Variable.of(trigger, "value") to
                            schema.fromObjectField(triggerFragment, listOf("b")),
                    )
                },
            )

        val result = resolveTrigger(testWorld)
        val payloadType = testWorld.schema.type("Payload") as Schema.ObjectType

        assertEquals(
            setOf("early", "late"),
            context(testWorld.assumptions) {
                requireNotNull(producerDemand)
                    .merge(payloadType)
                    .instantiateBindings()
                    .groundKeys()
                    .mapTo(linkedSetOf()) { it.field.fieldName }
            },
        )
        assertEquals(
            Value.Int.of(9),
            result.getValue(
                Value.GroundKey.of(
                    testWorld.schema.objectField("Query", "trigger"),
                    emptyMap(),
                ),
            ).get(),
        )
    }

    @Test
    fun `late equality merges disjoint demand into one exact application`() {
        var producerApplications = 0
        var producerDemand: SelectionForest? = null
        val triggerFragment =
            """
            fragment Trigger on Query {
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
                      trigger: Int!
                      a(name: String!): Payload!
                      b: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val a =
                        Value.GroundKey.of(
                            schema.objectField("Query", "a"),
                            mapOf("name" to "same"),
                        )
                    val one =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "one"),
                            emptyMap(),
                        )
                    val two =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "two"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "trigger") to
                            fieldResolverOf(schema.fragmentFrom(triggerFragment)) { input, _ ->
                                val payload = input.fieldValues.getValue(a) as Value.Object
                                val first = payload.fieldValues.getValue(one) as Value.Int
                                val second = payload.fieldValues.getValue(two) as Value.Int
                                Value.Int.of(first.intValue + second.intValue)
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
                    val trigger = schema.objectField("Query", "trigger")
                    mapOf(
                        Value.Variable.of(trigger, "name") to
                            schema.fromObjectField(triggerFragment, listOf("b")),
                    )
                },
            )

        val result = resolveTrigger(testWorld)
        val payloadType = testWorld.schema.type("Payload") as Schema.ObjectType

        assertEquals(1, producerApplications)
        assertEquals(
            setOf("one", "two"),
            context(testWorld.assumptions) {
                requireNotNull(producerDemand)
                    .merge(payloadType)
                    .instantiateBindings()
                    .groundKeys()
                    .mapTo(linkedSetOf()) { it.field.fieldName }
            },
        )
        assertTrue(
            result.getValue(
                Value.GroundKey.of(
                    testWorld.schema.objectField("Query", "trigger"),
                    emptyMap(),
                ),
            ).get() == Value.Int.of(8),
        )
    }

    @Test
    fun `late variable demand enters a published object with retained fixed input`() {
        val applications = mutableListOf<String>()
        val triggerFragment =
            """
            fragment Trigger on Query {
              parent {
                early
                activate(value: ${'$'}value)
              }
              provider
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      early: Int!
                      late: Int!
                      activate(value: Int!): Int!
                    }

                    type Query {
                      trigger: Int!
                      parent: Payload!
                      provider: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val parentKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "parent"),
                            emptyMap(),
                        )
                    val activateKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "activate"),
                            mapOf("value" to 4),
                        )
                    val lateKey =
                        Value.GroundKey.of(
                            schema.objectField("Payload", "late"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.objectField("Query", "trigger") to
                            fieldResolverOf(schema.fragmentFrom(triggerFragment)) { input, _ ->
                                val payload =
                                    input.fieldValues.getValue(parentKey) as Value.Object
                                payload.fieldValues.getValue(activateKey)
                            }.observeApplications { _, _, _ ->
                                applications += "trigger"
                            },
                        schema.objectField("Query", "parent") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "early" setTo 1
                                    "late" setTo 9
                                }
                            }.observeApplications { _, _, _ ->
                                applications += "parent"
                            },
                        schema.objectField("Query", "provider") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(4)
                            }.observeApplications { _, _, _ ->
                                applications += "provider"
                            },
                        schema.objectField("Payload", "activate") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Activate on Payload { late }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(lateKey)
                            }.observeApplications { _, _, _ ->
                                applications += "activate"
                            },
                    )
                },
                variableProviders = { schema ->
                    val trigger = schema.objectField("Query", "trigger")
                    mapOf(
                        Value.Variable.of(trigger, "value") to
                            schema.fromObjectField(triggerFragment, listOf("provider")),
                    )
                },
            )

        val result = resolveTrigger(testWorld)

        assertEquals(listOf("parent", "provider", "activate", "trigger"), applications)
        assertEquals(
            Value.Int.of(9),
            result.getValue(
                Value.GroundKey.of(
                    testWorld.schema.objectField("Query", "trigger"),
                    emptyMap(),
                ),
            ).get(),
        )
    }

    private fun resolveTrigger(testWorld: TestWorld): model.EngineResult.Object {
        val world: Assumptions = testWorld.assumptions
        return context(world) {
            world.objectOf("Query").resolve(
                world.fragmentFrom("fragment ignored on Query { trigger }").subselections,
            )
        }
    }
}
