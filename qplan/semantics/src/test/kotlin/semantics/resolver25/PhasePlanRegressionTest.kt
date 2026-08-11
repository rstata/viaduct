package semantics.resolver25

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals

class PhasePlanRegressionTest {
    @Test
    fun `accepts two path-variable owners with an acyclic canonical branch order`() {
        val outerFragment =
            """
            fragment Outer on Query {
              source
              middle(value: ${'$'}a)
            }
            """.trimIndent()
        val middleFragment =
            """
            fragment Middle on Query {
              source
              sink(value: ${'$'}b)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      outer: Int!
                      source: Int!
                      middle(value: Int!): Int!
                      sink(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val middleKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "middle"),
                            mapOf("value" to 7),
                        )
                    val sinkKey =
                        Value.GroundKey.of(
                            schema.objectField("Query", "sink"),
                            mapOf("value" to 7),
                        )
                    mapOf(
                        schema.objectField("Query", "outer") to
                            fieldResolverOf(schema.fragmentFrom(outerFragment)) { input, _ ->
                                input.fieldValues.getValue(middleKey)
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(7)
                            },
                        schema.objectField("Query", "middle") to
                            fieldResolverOf(schema.fragmentFrom(middleFragment)) { input, _ ->
                                input.fieldValues.getValue(sinkKey)
                            },
                        schema.objectField("Query", "sink") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val outer = schema.objectField("Query", "outer")
                    val middle = schema.objectField("Query", "middle")
                    mapOf(
                        Value.Variable.of(outer, "a") to
                            schema.fromObjectField(
                                outerFragment,
                                listOf("source"),
                            ),
                        Value.Variable.of(middle, "b") to
                            schema.fromObjectField(
                                middleFragment,
                                listOf("source"),
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val outerKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "outer"),
                emptyMap(),
            )

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(
                    world.fragmentFrom(
                        "fragment ignored on Query { outer }",
                    ).subselections,
                )
            }

        assertEquals(Value.Int.of(7), resolved.getValue(outerKey).get())
    }
}
