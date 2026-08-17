package semantics.resolver25

import java.time.Duration
import model.EngineResult
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.resolver25StructuralSignatures
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AdversarialRegressionTest {
    @Test
    fun `nested provider propagates null through a nullable intermediate`() {
        assertNestedProviderShortCircuit(provided = null, passiveIntermediate = false)
    }

    @Test
    fun `nested provider propagates error through an intermediate`() {
        assertNestedProviderShortCircuit(provided = Value.Error, passiveIntermediate = false)
    }

    @Test
    fun `nested provider propagates null through a passive intermediate`() {
        assertNestedProviderShortCircuit(provided = null, passiveIntermediate = true)
    }

    @Test
    fun `nested provider propagates error through a passive intermediate`() {
        assertNestedProviderShortCircuit(provided = Value.Error, passiveIntermediate = true)
    }

    private fun assertNestedProviderShortCircuit(
        provided: Value.Output?,
        passiveIntermediate: Boolean,
    ) {
        val expectedInput = provided as Value.Input?
        val expectedResult = if (provided == Value.Error) ErrorEngineResult else null
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            val providerSelection =
                if (passiveIntermediate) {
                    "box { nested { value } }"
                } else {
                    "box { value }"
                }
            val providedResult = if (provided == Value.Error) "\"ERROR\"" else "null"
            val boxResult =
                if (passiveIntermediate) {
                    "{nested: $providedResult}"
                } else {
                    providedResult
                }
            val providerPath =
                if (passiveIntermediate) {
                    """["box", "nested", "value"]"""
                } else {
                    """["box", "value"]"""
                }
            val testWorld =
                TestWorld.fromDSL(
                    schemaSDL =
                        """
                        extend type Query {
                          result: Int
                            @resolver(
                              of: "$providerSelection consume(value: ${'$'}value)"
                              pathVars: [{name: "value", path: $providerPath}]
                              result: "value(consume)"
                            )
                          box: Box @resolver(result: $boxResult)
                          consume(value: Int): Int
                            @resolver(result: "value(${'$'}value)")
                        }

                        type Box {
                          value: Int
                          nested: Nested
                        }

                        type Nested {
                          value: Int
                        }
                        """.trimIndent(),
                )
            val world = testWorld.assumptions
            val resultKey =
                Value.GroundKey.of(
                    world.schema.objectField("Query", "result"),
                    emptyMap(),
                )

            val observation =
                observeWithLifecycleValidation(
                    world = world,
                    root = world.objectOf("Query"),
                    selections =
                        world.fragmentFrom(
                            "fragment ignored on Query { result }",
                        ).subselections,
                )
            val resolved: ObjectEngineResult = observation.result
            val signatures = observation.lifecycleEvents.resolver25StructuralSignatures()
            assertContains(signatures, Resolver25StructuralSignature.NESTED_PROVIDER_PATH)
            assertContains(
                signatures,
                if (provided == null) {
                    Resolver25StructuralSignature.PROVIDER_NULL_SHORT_CIRCUIT
                } else {
                    Resolver25StructuralSignature.PROVIDER_ERROR_SHORT_CIRCUIT
                },
            )

            assertEquals<EngineResult?>(expectedResult, resolved.getCell(resultKey).getValue().get())
            assertEquals(
                expectedInput,
                world.getBinding(
                    Value.Variable
                        .of(resultKey.field, "value")
                        .stamp(listOf(resultKey)),
                ),
            )
        }
    }
}
