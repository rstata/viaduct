package semantics.resolver25

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.resolver25StructuralSignatures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MixedVariablePhaseRegressionTest {
    @Test
    fun `binds a known resolver argument before its nested path variable`() {
        val resultFragment =
            """
            fragment Result on Query {
              bridge(value: 7) {
                consume(value: ${'$'}pathValue)
              }
              source
            }
            """.trimIndent()
        val bridgeFragment =
            """
            fragment Bridge on Query {
              seed(value: ${'$'}argumentValue)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Item {
                      consume(value: Int!): Int!
                    }

                    type Query {
                      result: Int!
                      source: Int!
                      bridge(value: Int!): Item!
                      seed(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val bridge =
                        Value.GroundKey.of(
                            schema.objectField("Query", "bridge"),
                            mapOf("value" to 7),
                        )
                    val consume =
                        Value.GroundKey.of(
                            schema.objectField("Item", "consume"),
                            mapOf("value" to 1),
                        )
                    val seedOne =
                        Value.GroundKey.of(
                            schema.objectField("Query", "seed"),
                            mapOf("value" to 1),
                        )
                    val seedSeven =
                        Value.GroundKey.of(
                            schema.objectField("Query", "seed"),
                            mapOf("value" to 7),
                        )
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val item = input.fieldValues.getValue(bridge) as Value.Object
                                item.fieldValues.getValue(consume)
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Source on Query { seed(value: 1) }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(seedOne)
                            },
                        schema.objectField("Query", "bridge") to
                            fieldResolverOf(schema.fragmentFrom(bridgeFragment)) { input, _ ->
                                check(input.fieldValues.getValue(seedSeven) == Value.Int.of(7))
                                schema.objectOf("Item")
                            },
                        schema.objectField("Query", "seed") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                        schema.objectField("Item", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    val bridge = schema.objectField("Query", "bridge")
                    mapOf(
                        Value.Variable.of(result, "pathValue") to
                            schema.fromObjectField(resultFragment, listOf("source")),
                        Value.Variable.of(bridge, "argumentValue") to
                            schema.fromArgument(bridge, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val observation =
            observeWithLifecycleValidation(
                world = world,
                root = world.objectOf("Query"),
                selections =
                    world.fragmentFrom(
                        "fragment ignored on Query { result }",
                    ).subselections,
            )
        val result = observation.result
        val signatures = observation.lifecycleEvents.resolver25StructuralSignatures()
        assertContains(
            signatures,
            Resolver25StructuralSignature.MIXED_BINDING_SOURCES_COACTIVATED,
        )
        assertContains(
            signatures,
            Resolver25StructuralSignature.NESTED_VARIABLE_USE,
        )

        assertEquals(
            Value.Int.of(1),
            result
                .getValue(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "result"),
                        emptyMap(),
                    ),
                ).get(),
        )
    }

    @Test
    fun `does not let a future distinct key block a ready resolver instance`() {
        val resultFragment =
            """
            fragment Result on Query {
              source
              dependent(value: ${'$'}pathValue)
            }
            """.trimIndent()
        val dependentFragment =
            """
            fragment Dependent on Query {
              producer(key: ${'$'}argumentValue)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      source: String!
                      dependent(value: String!): Int!
                      producer(key: String!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val dependent =
                        Value.GroundKey.of(
                            schema.objectField("Query", "dependent"),
                            mapOf("value" to "late"),
                        )
                    val earlyProducer =
                        Value.GroundKey.of(
                            schema.objectField("Query", "producer"),
                            mapOf("key" to "early"),
                        )
                    val lateProducer =
                        Value.GroundKey.of(
                            schema.objectField("Query", "producer"),
                            mapOf("key" to "late"),
                        )
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.fieldValues.getValue(dependent)
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment Source on Query {
                                      producer(key: "early")
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                check(input.fieldValues.getValue(earlyProducer) == Value.Int.of(1))
                                Value.String.of("late")
                            },
                        schema.objectField("Query", "dependent") to
                            fieldResolverOf(schema.fragmentFrom(dependentFragment)) { input, _ ->
                                input.fieldValues.getValue(lateProducer)
                            },
                        schema.objectField("Query", "producer") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                when (
                                    (arguments.fieldValues.getValue("key") as Value.String)
                                        .stringValue
                                ) {
                                    "early" -> Value.Int.of(1)
                                    "late" -> Value.Int.of(2)
                                    else -> error("Unexpected producer key")
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    val dependent = schema.objectField("Query", "dependent")
                    mapOf(
                        Value.Variable.of(result, "pathValue") to
                            schema.fromObjectField(resultFragment, listOf("source")),
                        Value.Variable.of(dependent, "argumentValue") to
                            schema.fromArgument(dependent, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val observation =
            observeWithLifecycleValidation(
                world = world,
                root = world.objectOf("Query"),
                selections =
                    world.fragmentFrom(
                        "fragment ignored on Query { result }",
                    ).subselections,
            )
        val result = observation.result
        val signatures = observation.lifecycleEvents.resolver25StructuralSignatures()
        assertContains(
            signatures,
            Resolver25StructuralSignature.MIXED_BINDING_SOURCES_COACTIVATED,
        )
        assertContains(
            signatures,
            Resolver25StructuralSignature.STAGGERED_DISTINCT_KEYS,
        )

        assertEquals(
            Value.Int.of(2),
            result
                .getValue(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "result"),
                        emptyMap(),
                    ),
                ).get(),
        )
    }
}
