package semantics.resolver25

import model.requireType
import model.requireObjectField
import model.Arguments
import model.EngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import viaduct.graphql.schema.ViaductSchema
import model.SelectionForest
import model.VariableBinding
import model.emptyFragmentOf
import model.fragmentFrom
import model.instantiateBindings
import model.merge
import model.objectOf
import model.operationSelectionsFrom
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.resolver25StructuralSignatures
import semantics.contract.selectionValues
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.api.EngineObjectData

class DemandSealingTest {
    @Test
    fun `binds a direct scalar sibling before grounding its consumer key`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source: Int! @resolver(result: 7)
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value, ${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveResult(testWorld)

        assertEquals(14, resolved.getCell(resultKey).getValue().get())
        assertEquals(
            VariableBinding.of(7),
            testWorld.assumptions.getBinding(
                Arguments.Variable
                    .of(resultKey.field, "value")
                    .instantiate(ResolverOccurrenceId.at(resolved, listOf(resultKey)))
                    .let { variable -> requireNotNull(variable.instanceId) },
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
                    val aKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Query", "a"), emptyMap())
                    val cKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Query", "c"),
                            mapOf("value" to 4),
                        )
                    val lateKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "late"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.requireObjectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.selectionValues().getValue(cKey.field.name)
                            },
                        schema.requireObjectField("Query", "a") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "early" setTo 1
                                    "late" setTo 9
                                }
                            }.observeApplications { _, _, demand ->
                                producerApplications += 1
                                producerDemand = demand
                            },
                        schema.requireObjectField("Query", "b") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                4
                            },
                        schema.requireObjectField("Query", "c") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment C on Query { a { late } }",
                                ),
                            ) { input, _ ->
                                val payload =
                                    input.selectionValues().getValue(aKey.field.name) as EngineObjectData.Sync
                                payload.selectionValues().getValue(lateKey.field.name)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "value") to
                            schema.fromObjectField(resultFragment, listOf("b")),
                    )
                },
            )

        val observation = observeResult(testWorld)
        val resolved = observation.result
        val payloadType = testWorld.schema.requireType("Payload") as ViaductSchema.Object
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
                    .mapTo(linkedSetOf()) { key -> key.field.name }
            },
        )
        assertEquals(
            9,
            resolved
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        testWorld.schema.requireObjectField("Query", "result"),
                        emptyMap(),
                    ),
                ).getValue().get(),
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
                    val oneKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "one"),
                            emptyMap(),
                        )
                    val twoKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "two"),
                            emptyMap(),
                        )
                    mapOf(
                        schema.requireObjectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                val exact =
                                    input.selectionValues().getValue("exact") as EngineObjectData.Sync
                                val symbolic =
                                    input.selectionValues().getValue("symbolic") as EngineObjectData.Sync
                                val one =
                                    exact.selectionValues().getValue(oneKey.field.name) as Int
                                val two =
                                    symbolic.selectionValues().getValue(twoKey.field.name) as Int
                                one + two
                            },
                        schema.requireObjectField("Query", "a") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Payload") {
                                    "one" setTo 3
                                    "two" setTo 5
                                }
                            }.observeApplications { _, _, demand ->
                                producerApplications += 1
                                producerDemand = demand
                            },
                        schema.requireObjectField("Query", "b") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                "same"
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "name") to
                            schema.fromObjectField(resultFragment, listOf("b")),
                    )
                },
            )

        val observation = observeResult(testWorld)
        val resolved = observation.result
        val payloadType = testWorld.schema.requireType("Payload") as ViaductSchema.Object

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
                    .mapTo(linkedSetOf()) { key -> key.field.name }
            },
        )
        assertTrue(
            resolved
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        testWorld.schema.requireObjectField("Query", "result"),
                        emptyMap(),
                    ),
                ).getValue().get() == 8,
        )
    }

    @Test
    fun `binds a three-component path variable at a resolver-backed terminal field`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "box { nested { value } } consume(value: ${'$'}value)"
                          pathVars: [
                            {name: "value", path: ["box", "nested", "value"]}
                          ]
                          result: "sum(consume)"
                        )
                      box: Box! @resolver(result: {nested: {}})
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Box {
                      nested: Nested!
                    }

                    type Nested {
                      value: Int! @resolver(result: 11)
                    }
                    """.trimIndent(),
            )

        val observation = observeResult(testWorld)
        val resolved = observation.result
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        assertEquals(11, resolved.getCell(resultKey).getValue().get())
        assertContains(
            observation.lifecycleEvents.resolver25StructuralSignatures(),
            Resolver25StructuralSignature.NESTED_PROVIDER_PATH,
        )
        assertEquals(
            VariableBinding.of(11),
            testWorld.assumptions.getBinding(
                Arguments.Variable
                    .of(resultKey.field, "value")
                    .instantiate(ResolverOccurrenceId.at(resolved, listOf(resultKey)))
                    .let { variable -> requireNotNull(variable.instanceId) },
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
                    val aKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Query", "a"), emptyMap())
                    val zKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField("Query", "z"), emptyMap())
                    val earlyKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "early"),
                            emptyMap(),
                        )
                    val lateKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "late"),
                            emptyMap(),
                        )
                    val seedEarlyKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Seeds", "early"),
                            emptyMap(),
                        )
                    val seedLateKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Seeds", "late"),
                            emptyMap(),
                        )
                    val cKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Query", "c"),
                            mapOf("value" to 4),
                        )
                    mapOf(
                        schema.requireObjectField("Query", "result") to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.selectionValues().getValue(cKey.field.name)
                            },
                        schema.requireObjectField("Query", "a") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment A on Query { z { early late } }",
                                ),
                            ) { input, _ ->
                                val seeds =
                                    input.selectionValues().getValue(zKey.field.name) as EngineObjectData.Sync
                                schema.objectOf("Payload") {
                                    "early" setTo
                                        seeds.selectionValues().getValue(seedEarlyKey.field.name)
                                    "late" setTo
                                        seeds.selectionValues().getValue(seedLateKey.field.name)
                                }
                            }.observeApplications { _, _, demand ->
                                producerApplications += 1
                                producerDemand = demand
                            },
                        schema.requireObjectField("Query", "b") to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment B on Query { a { early } }"),
                            ) { input, _ ->
                                val payload =
                                    input.selectionValues().getValue(aKey.field.name) as EngineObjectData.Sync
                                val value =
                                    payload.selectionValues().getValue(earlyKey.field.name)
                                schema.objectOf("Box") {
                                    "value" setTo value
                                }
                            },
                        schema.requireObjectField("Query", "c") to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment C on Query { a { late } }"),
                            ) { input, arguments ->
                                consumerApplications += 1
                                val value = arguments.fieldValues.getValue("value") as Int
                                consumerArguments += value
                                val payload =
                                    input.selectionValues().getValue(aKey.field.name) as EngineObjectData.Sync
                                payload.selectionValues().getValue(lateKey.field.name)
                            },
                        schema.requireObjectField("Query", "z") to
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
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "value") to
                            schema.fromObjectField(
                                resultFragment,
                                listOf("b", "value"),
                            ),
                    )
                },
            )

        val resolved: ObjectEngineResult =
            context(testWorld.assumptions) {
                resolve(
                    testWorld.assumptions
                        .operationSelectionsFrom(
                            "query { result c(value: 7) }",
                        ),
                )
            }
        val payloadType = testWorld.schema.requireType("Payload") as ViaductSchema.Object
        val seedsType = testWorld.schema.requireType("Seeds") as ViaductSchema.Object

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
                    .mapTo(linkedSetOf()) { key -> key.field.name }
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
                    .mapTo(linkedSetOf()) { key -> key.field.name }
            },
        )
        assertEquals(
            9,
            resolved
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        testWorld.schema.requireObjectField("Query", "result"),
                        emptyMap(),
                    ),
                ).getValue().get(),
        )
    }

    private fun resolveResult(testWorld: TestWorld): ObjectEngineResult {
        return observeResult(testWorld).result
    }

    private fun observeResult(testWorld: TestWorld): Resolver25ResolutionObservation {
        val world = testWorld.assumptions
        return observeWithLifecycleValidation(
            world = world,
            root = world.objectOf("Query"),
            selections =
                world.operationSelectionsFrom(
                    "query { result }",
                ),
        )
    }
}
