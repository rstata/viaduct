package semantics.resolver25

import model.requireObjectField
import model.ObjectEngineResult
import model.objectOf
import model.operationSelectionsFrom
import model.testing.TestWorld
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.assertDistinctArguments
import semantics.contract.resolver25StructuralSignatures
import semantics.contract.selectionValues
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixedVariablePhaseRegressionTest {
    @Test
    fun `binds a known resolver argument before its nested path variable`() {
        var observedBridge = false
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "bridge(value: 7) { consume(value: ${'$'}pathValue) } source"
                          pathVars: [{name: "pathValue", path: ["source"]}]
                          result: "sum(bridge.consume)"
                        )
                      source: Int!
                        @resolver(of: "seed(value: 1)", result: "sum(seed)")
                      bridge(value: Int!): Item!
                        @resolver(
                          of: "seed(value: ${'$'}value)"
                          result: {}
                        )
                      seed(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Item {
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    if (
                        field.containingDef.name == "Query" &&
                        field.name == "bridge"
                    ) {
                        val seed =
                            input.selectionValues().entries
                                .single { (key, _) ->
                                    key == "seed" || key.startsWith("seed(")
                                }.value
                        require(seed == 7)
                        observedBridge = true
                    }
                },
            )
        val world = testWorld.assumptions
        val observation =
            observeWithLifecycleValidation(
                world = world,
                root = world.objectOf("Query"),
                selections =
                    world.operationSelectionsFrom(
                        "query { result }",
                    ),
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
            1,
            result
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Query", "result"),
                        emptyMap(),
                    ),
                ).getValue().get(),
        )
        assertTrue(observedBridge)
        testWorld.applicationArguments.assertDistinctArguments(
            world.schema.requireObjectField("Query", "bridge"),
            mapOf("value" to 7),
        )
        testWorld.applicationArguments.assertDistinctArguments(
            world.schema.requireObjectField("Query", "seed"),
            mapOf("value" to 1),
            mapOf("value" to 7),
        )
        testWorld.applicationArguments.assertDistinctArguments(
            world.schema.requireObjectField("Item", "consume"),
            mapOf("value" to 1),
        )
    }

    @Test
    fun `does not let a future distinct key block a ready resolver instance`() {
        var observedSource = false
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source dependent(value: ${'$'}pathValue)"
                          pathVars: [{name: "pathValue", path: ["source"]}]
                          result: "sum(dependent)"
                        )
                      source: Int!
                        @resolver(of: "producer(key: 1)", result: 2)
                      dependent(value: Int!): Int!
                        @resolver(
                          of: "producer(key: ${'$'}value)"
                          result: "sum(producer)"
                        )
                      producer(key: Int!): Int!
                        @resolver(result: "sum(${'$'}key)")
                    }
                    """.trimIndent(),
                applicationObserver = { field, input, _, _ ->
                    if (
                        field.containingDef.name == "Query" &&
                        field.name == "source"
                    ) {
                        val producer =
                            input.selectionValues().entries
                                .single { (key, _) ->
                                    key == "producer" || key.startsWith("producer(")
                                }.value
                        require(producer == 1)
                        observedSource = true
                    }
                },
            )
        val world = testWorld.assumptions
        val observation =
            observeWithLifecycleValidation(
                world = world,
                root = world.objectOf("Query"),
                selections =
                    world.operationSelectionsFrom(
                        "query { result }",
                    ),
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
            2,
            result
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Query", "result"),
                        emptyMap(),
                    ),
                ).getValue().get(),
        )
        assertTrue(observedSource)
        testWorld.applicationArguments.assertDistinctArguments(
            world.schema.requireObjectField("Query", "dependent"),
            mapOf("value" to 2),
        )
        testWorld.applicationArguments.assertDistinctArguments(
            world.schema.requireObjectField("Query", "producer"),
            mapOf("key" to 1),
            mapOf("key" to 2),
        )
    }
}
