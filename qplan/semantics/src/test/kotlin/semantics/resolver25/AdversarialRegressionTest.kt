package semantics.resolver25

import java.time.Duration
import model.EngineResult
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import kotlin.test.assertEquals

class AdversarialRegressionTest {
    @Test
    fun `nested provider propagates null through a nullable intermediate`() {
        assertNestedProviderShortCircuit(provided = null)
    }

    @Test
    fun `nested provider propagates error through an intermediate`() {
        assertNestedProviderShortCircuit(provided = Value.Error)
    }

    private fun assertNestedProviderShortCircuit(provided: Value.Output?) {
        val expectedInput = provided as Value.Input?
        val expectedResult = provided as EngineResult?
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
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
                          value: Int
                        }

                        type Query {
                          result: Int
                          box: Box
                          consume(value: Int): Int
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val consume = schema.objectField("Query", "consume")
                        val consumeKey =
                            Value.GroundKey.of(
                                consume,
                                mapOf("value" to expectedInput),
                            )
                        mapOf(
                            schema.objectField("Query", "result") to
                                fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                    input.fieldValues.getValue(consumeKey)
                                },
                            schema.objectField("Query", "box") to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                    provided
                                },
                            consume to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                    arguments.fieldValues.getValue("value") as Value.Output?
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
            val world = testWorld.assumptions
            val resultKey =
                Value.GroundKey.of(
                    world.schema.objectField("Query", "result"),
                    emptyMap(),
                )

            val resolved: EngineResult.Object =
                context(world) {
                    world.objectOf("Query").resolve(
                        world.fragmentFrom(
                            "fragment ignored on Query { result }",
                        ).subselections,
                    )
                }

            assertEquals<EngineResult?>(expectedResult, resolved.getValue(resultKey).get())
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
