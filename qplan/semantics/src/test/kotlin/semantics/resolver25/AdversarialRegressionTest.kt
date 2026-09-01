package semantics.resolver25

import model.requireObjectField
import model.Arguments
import java.time.Duration
import model.EngineResult
import model.EngineErrorData
import model.EngineOutputData
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.VariableBinding
import model.objectOf
import model.operationSelectionsFrom
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import semantics.contract.Resolver25StructuralSignature
import semantics.contract.resolver25StructuralSignatures
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AdversarialRegressionTest {
    @Test
    fun `nested provider propagates null through a nullable intermediate`() {
        assertNestedProviderShortCircuit(provided = null, passiveIntermediate = false)
    }

    @Test
    fun `nested provider propagates error through an intermediate`() {
        assertNestedProviderShortCircuit(provided = EngineErrorData.of(), passiveIntermediate = false)
    }

    @Test
    fun `nested provider propagates null through a passive intermediate`() {
        assertNestedProviderShortCircuit(provided = null, passiveIntermediate = true)
    }

    @Test
    fun `nested provider propagates error through a passive intermediate`() {
        assertNestedProviderShortCircuit(provided = EngineErrorData.of(), passiveIntermediate = true)
    }

    private fun assertNestedProviderShortCircuit(
        provided: EngineOutputData?,
        passiveIntermediate: Boolean,
    ) {
        val isError = provided is EngineErrorData
        val expectedBinding =
            if (isError) {
                VariableBinding.Error
            } else {
                VariableBinding.of(null)
            }
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            val providerSelection =
                if (passiveIntermediate) {
                    "box { nested { value } }"
                } else {
                    "box { value }"
                }
            val providedResult = if (isError) "\"ERROR\"" else "null"
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
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "result"),
                    emptyMap(),
                )

            val observation =
                observeWithLifecycleValidation(
                    world = world,
                    root = world.objectOf("Query"),
                    selections =
                        world.operationSelectionsFrom(
                            "query { result }",
                        ),
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

            val resultValue = resolved.getCell(resultKey).getValue().get()
            if (isError) {
                assertIs<ErrorEngineResult>(resultValue)
            } else {
                assertEquals<EngineResult?>(null, resultValue)
            }
            assertEquals(
                expectedBinding,
                world.getBinding(
                    Arguments.Variable
                        .of(resultKey.field, "value")
                        .instantiate(ResolverOccurrenceId.at(listOf(resultKey)))
                        .let { variable -> requireNotNull(variable.instanceId) },
                ),
            )
        }
    }
}
