package semantics.resolver26

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import org.junit.jupiter.api.Disabled
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FromObjectFieldSingletonCoercionRegressionTest {
    @Disabled(
        "Enable when Resolver26 applies GraphQL singleton coercion to FromObjectField values before grounding nested-list arguments.",
    )
    @Test
    fun `singleton coerces a scalar object-field value through two input-list layers`() {
        val resultFragment =
            """
            fragment Result on Query {
              source
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        var consumedArgument: Value.Input? = null
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      result: Int!
                      source: Int!
                      consume(value: [[Int!]!]!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val consume = schema.objectField("Query", "consume")
                    val consumeKey =
                        Value.GroundKey.of(
                            consume,
                            mapOf("value" to listOf(listOf(7))),
                        )
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
                                consumedArgument = arguments.fieldValues.getValue("value")
                                val outer = consumedArgument as Value.InputList
                                val inner = outer.values.single() as Value.InputList
                                val value = inner.values.single() as Value.Int
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
        val world = testWorld.assumptions
        val resultKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "result"),
                emptyMap(),
            )
        val fragment =
            world.fragmentFrom("fragment QueryResult on Query { result }")

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val outer = assertIs<Value.InputList>(consumedArgument)
        val inner = assertIs<Value.InputList>(outer.values.single())

        assertEquals(listOf(Value.Int.of(7)), inner.values)
        assertEquals(Value.Int.of(14), resolved.getValue(resultKey).get())
        assertTrue(context(world) { resolved.correctResolution(fragment) })
    }
}
