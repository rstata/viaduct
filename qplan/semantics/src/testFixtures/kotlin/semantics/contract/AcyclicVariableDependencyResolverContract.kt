package semantics.contract

import model.EngineResult
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals

interface AcyclicVariableDependencyResolverContract : ResolverContract {
    @Test
    fun `accepts an acyclic path-variable dependency chain`() {
        val aFragment =
            "fragment A on Query { pa b(value: ${'$'}fromPa) }"
        val qFragment =
            "fragment Q on Query { a(seed: 1) }"
        val dFragment =
            "fragment D on Query { p2 a(seed: ${'$'}fromP2) }"
        val cFragment =
            "fragment C on Query { q d(seed: ${'$'}fromQ) }"
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      pa: Int!
                      b(value: Int!): Int!
                      a(seed: Int!): Int!
                      q: Int!
                      p2: Int!
                      d(seed: Int!): Int!
                      c: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val b = schema.objectField("Query", "b")
                    val a = schema.objectField("Query", "a")
                    val d = schema.objectField("Query", "d")
                    mapOf(
                        schema.objectField("Query", "pa") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                        b to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                        a to
                            fieldResolverOf(schema.fragmentFrom(aFragment)) { input, _ ->
                                input.fieldValues.getValue(
                                    Value.GroundKey.of(b, mapOf("value" to 1)),
                                )
                            },
                        schema.objectField("Query", "q") to
                            fieldResolverOf(schema.fragmentFrom(qFragment)) { input, _ ->
                                input.fieldValues.getValue(
                                    Value.GroundKey.of(a, mapOf("seed" to 1)),
                                )
                            },
                        schema.objectField("Query", "p2") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                        d to
                            fieldResolverOf(schema.fragmentFrom(dFragment)) { input, _ ->
                                input.fieldValues.getValue(
                                    Value.GroundKey.of(a, mapOf("seed" to 1)),
                                )
                            },
                        schema.objectField("Query", "c") to
                            fieldResolverOf(schema.fragmentFrom(cFragment)) { input, _ ->
                                input.fieldValues.getValue(
                                    Value.GroundKey.of(d, mapOf("seed" to 1)),
                                )
                            },
                    )
                },
                variableProviders = { schema ->
                    val a = schema.objectField("Query", "a")
                    val d = schema.objectField("Query", "d")
                    val c = schema.objectField("Query", "c")
                    mapOf(
                        Value.Variable.of(a, "fromPa") to
                            schema.fromObjectField(aFragment, listOf("pa")),
                        Value.Variable.of(d, "fromP2") to
                            schema.fromObjectField(dFragment, listOf("p2")),
                        Value.Variable.of(c, "fromQ") to
                            schema.fromObjectField(cFragment, listOf("q")),
                    )
                },
            )
        val world = testWorld.assumptions
        val cKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "c"),
                emptyMap(),
            )

        val resolved =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom("fragment ignored on Query { c }"),
            )

        assertEquals(Value.Int.of(1), resolved.getCell(cKey).get())
    }

    @Test
    fun `accepts an acyclic mixed-variable dependency chain`() {
        val outerFragment =
            """
            fragment Outer on Query {
              q5
              q2(value: ${'$'}fromQ5)
            }
            """.trimIndent()
        val q2Fragment =
            "fragment Q2 on Query { q7(value: ${'$'}fromQ2Arg) }"
        val q7Fragment =
            "fragment Q7 on Query { q1(value: ${'$'}fromQ7Arg) }"
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      outer: Int!
                      q1(value: Int!): Int!
                      q2(value: Int!): Int!
                      q5: Int!
                      q7(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val q1 = schema.objectField("Query", "q1")
                    val q2 = schema.objectField("Query", "q2")
                    val q7 = schema.objectField("Query", "q7")
                    val q1Key = Value.GroundKey.of(q1, mapOf("value" to 1))
                    val q2Key = Value.GroundKey.of(q2, mapOf("value" to 1))
                    val q7Key = Value.GroundKey.of(q7, mapOf("value" to 1))
                    mapOf(
                        schema.objectField("Query", "outer") to
                            fieldResolverOf(schema.fragmentFrom(outerFragment)) { input, _ ->
                                input.fieldValues.getValue(q2Key)
                            },
                        q1 to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                        q2 to
                            fieldResolverOf(schema.fragmentFrom(q2Fragment)) { input, _ ->
                                input.fieldValues.getValue(q7Key)
                            },
                        schema.objectField("Query", "q5") to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Q5 on Query { q1(value: 1) }"),
                            ) { input, _ ->
                                input.fieldValues.getValue(q1Key)
                            },
                        q7 to
                            fieldResolverOf(schema.fragmentFrom(q7Fragment)) { input, _ ->
                                input.fieldValues.getValue(q1Key)
                            },
                    )
                },
                variableProviders = { schema ->
                    val outer = schema.objectField("Query", "outer")
                    val q2 = schema.objectField("Query", "q2")
                    val q7 = schema.objectField("Query", "q7")
                    mapOf(
                        Value.Variable.of(outer, "fromQ5") to
                            schema.fromObjectField(outerFragment, listOf("q5")),
                        Value.Variable.of(q2, "fromQ2Arg") to
                            schema.fromArgument(q2, "value"),
                        Value.Variable.of(q7, "fromQ7Arg") to
                            schema.fromArgument(q7, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val outerKey =
            Value.GroundKey.of(
                world.schema.objectField("Query", "outer"),
                emptyMap(),
            )

        val resolved: EngineResult.Object =
            resolveAndValidate(
                world,
                world.objectOf("Query"),
                world.fragmentFrom("fragment ignored on Query { outer }"),
            )

        assertEquals(Value.Int.of(1), resolved.getCell(outerKey).get())
    }
}
