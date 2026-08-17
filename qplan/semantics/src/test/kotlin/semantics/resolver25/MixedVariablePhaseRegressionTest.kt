package semantics.resolver25

import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.resolver25StructuralSignatures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MixedVariablePhaseRegressionTest {
    @Test
    fun `binds a known resolver argument before its nested path variable`() {
        var bridgeSeed: Value.Output? = null
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
                        field.containingType.typeName == "Query" &&
                        field.fieldName == "bridge"
                    ) {
                        bridgeSeed =
                            input.fieldValues.entries
                                .single { (key, _) -> key.field.fieldName == "seed" }
                                .value
                    }
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
                .getCell(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "result"),
                        emptyMap(),
                    ),
                ).getValue().get(),
        )
        assertEquals(Value.Int.of(7), bridgeSeed)
    }

    @Test
    fun `does not let a future distinct key block a ready resolver instance`() {
        var earlyProducer: Value.Output? = null
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
                        field.containingType.typeName == "Query" &&
                        field.fieldName == "source"
                    ) {
                        earlyProducer =
                            input.fieldValues.entries
                                .single { (key, _) -> key.field.fieldName == "producer" }
                                .value
                    }
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
                .getCell(
                    Value.GroundKey.of(
                        world.schema.objectField("Query", "result"),
                        emptyMap(),
                    ),
                ).getValue().get(),
        )
        assertEquals(Value.Int.of(1), earlyProducer)
    }
}
