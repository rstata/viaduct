package semantics.resolver04

import model.EngineResult
import model.Schema
import model.Value
import model.VariableCoordinate
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResolverGeneratedRegressionTest {
    @Test
    fun `broadens an already resolved nested object after binding a variable`() {
        val applications = linkedMapOf<String, Int>()
        fun applied(name: String) {
            applications[name] = applications.getOrDefault(name, 0) + 1
        }
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Object1 {
                      variableConsumer: Int!
                      common: String!
                      child: Object2!
                    }

                    type Object2 {
                      field2(arg: String): Int!
                    }

                    type Query {
                      source: Object1!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.field("Query", "source") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                applied("source")
                                schema.objectOf("Object1")
                            },
                        schema.field("Object1", "common") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Object1 {
                                      child {
                                        field2(arg: "literal")
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ ->
                                applied("common")
                                Value.String.of("bound")
                            },
                        schema.field("Object1", "variableConsumer") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Object1 {
                                      child {
                                        field2(arg: ${'$'}value)
                                      }
                                      common
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ ->
                                applied("variableConsumer")
                                Value.Int.of(1)
                            },
                        schema.field("Object1", "child") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Object1"),
                            ) { _, _ ->
                                applied("child")
                                schema.objectOf("Object2")
                            },
                        schema.field("Object2", "field2") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Object2"),
                            ) { _, _ ->
                                applied("field2")
                                Value.Int.of(2)
                            },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        VariableCoordinate.of(
                            schema.field("Object1", "variableConsumer") as Schema.ObjectField,
                            Value.Variable.of("value"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on Object1 { common }",
                            ).subselections.single(),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { source { variableConsumer } }",
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val source =
            assertIs<EngineResult.Object>(
                result.fetch(
                    Value.Key.of(world.schema.field("Query", "source"), emptyMap()),
                ).value,
            )

        assertEquals(
            Value.String.of("bound"),
            source.variableValues.getValue(Value.Variable.of("value")),
        )
        assertEquals(
            Value.Int.of(1),
            source.fetch(
                Value.Key.of(world.schema.field("Object1", "variableConsumer"), emptyMap()),
            ).value,
        )
        assertEquals(
            mapOf(
                "source" to 1,
                "child" to 1,
                "field2" to 2,
                "common" to 1,
                "variableConsumer" to 1,
            ),
            applications,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }
}
