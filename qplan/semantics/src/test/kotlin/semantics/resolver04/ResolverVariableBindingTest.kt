package semantics.resolver04

import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ImplementationArgumentDefaultWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.conformsToFragment
import semantics.correctresolution.conformsToResolvers
import semantics.correctresolution.conformsToTypename
import semantics.correctresolution.conformsToVariables
import semantics.correctresolution.correctResolution
import semantics.correctresolution.isClosedUnderResolverDemand
import semantics.correctresolution.rootedAndWellTyped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Field-relative variable evaluation, substitution, conversion, recursion, and null propagation.
 *
 * Keep tests here focused on producing and instantiating bindings rather than widening cells.
 */
class ResolverVariableBindingTest {
    @Test
    fun `resolves a field-relative variable before instantiating its object fragment`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      x: Int!
                      y(b: Int!): Int!
                      z: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val query = schema.query
                    val yKey = Value.ObjectKey.of(schema.objectField("Query", "y"), mapOf("b" to 2))
                    mapOf(
                        schema.field("Query", "x") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      y(b: ${'$'}b)
                                      z
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val y = input.fieldValues.getValue(yKey) as Value.Int
                                Value.Int.of(y.intValue * 5)
                            },
                        schema.field("Query", "y") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, arguments ->
                                val b = arguments.fieldValues.getValue("b") as Value.Int
                                Value.Int.of(b.intValue * 3)
                            },
                        schema.field("Query", "z") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> Value.Int.of(2) },
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.objectField("Query", "x")
                    val variable = Value.Variable.of("b", owner, path = null)
                    mapOf(
                        variable to
                            schema.provider(
                                """
                                fragment ignored on Query {
                                  z
                                }
                                """.trimIndent(),
                                "z",
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  x
                }
                """.trimIndent(),
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertEquals(
            Value.Int.of(30),
            result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "x"), emptyMap())).value,
        )
        assertEquals(
            Value.Int.of(2),
            result.variableValues.getValue(
                Value.Variable.of("b", world.schema.objectField("Query", "x"), path = null),
            ),
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `resolves recursive variable dependencies`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      x: Int!
                      y(b: Int!): Int!
                      z(c: Int!): Int!
                      raw: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.emptyFragmentOf("Query")
                    mapOf(
                        schema.field("Query", "x") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      y(b: ${'$'}b)
                                      z(c: ${'$'}c)
                                      raw
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val key = Value.ObjectKey.of(schema.objectField("Query", "y"), mapOf("b" to 6))
                                val y = input.fieldValues.getValue(key) as Value.Int
                                Value.Int.of(y.intValue * 7)
                            },
                        schema.field("Query", "y") to
                            model.testing.fieldResolverOf(empty) { _, arguments ->
                                val b = arguments.fieldValues.getValue("b") as Value.Int
                                Value.Int.of(b.intValue * 5)
                            },
                        schema.field("Query", "z") to
                            model.testing.fieldResolverOf(empty) { _, arguments ->
                                val c = arguments.fieldValues.getValue("c") as Value.Int
                                Value.Int.of(c.intValue * 3)
                            },
                        schema.field("Query", "raw") to
                            model.testing.fieldResolverOf(empty) { _, _ -> Value.Int.of(2) },
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.field("Query", "x") as Schema.ObjectField
                    mapOf(
                        Value.Variable.of("b", owner, path = null) to
                            schema.provider(
                                "fragment ignored on Query { z(c: ${'$'}c) }",
                                "z",
                            ),
                        Value.Variable.of("c", owner, path = null) to
                            schema.provider(
                                "fragment ignored on Query { raw }",
                                "raw",
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { x }")

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        val owner = world.schema.objectField("Query", "x")
        assertEquals(
            Value.Int.of(6),
            result.variableValues.getValue(Value.Variable.of("b", owner, path = null)),
        )
        assertEquals(
            Value.Int.of(2),
            result.variableValues.getValue(Value.Variable.of("c", owner, path = null)),
        )
        assertEquals(
            Value.Int.of(210),
            result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "x"), emptyMap())).value,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `instantiates variable references in nested object-fragment keys`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Child {
                      y(b: Int!): Int!
                    }

                    type User {
                      child: Child!
                      x: Int!
                      z: Int!
                    }

                    type Query {
                      viewer: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val childKey = Value.ObjectKey.of(schema.objectField("User", "child"), emptyMap())
                    val yKey = Value.ObjectKey.of(schema.objectField("Child", "y"), mapOf("b" to 2))
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("User") {
                                    "child" setTo schema.objectOf("Child")
                                    "z" setTo 2
                                }
                            },
                        schema.field("User", "x") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on User {
                                      child {
                                        y(b: ${'$'}b)
                                      }
                                      z
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val child = input.fieldValues.getValue(childKey) as Value.Object
                                child.fieldValues.getValue(yKey)
                            },
                        schema.field("Child", "y") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, arguments ->
                                val b = arguments.fieldValues.getValue("b") as Value.Int
                                Value.Int.of(b.intValue * 5)
                            },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        Value.Variable.of(
                            "b",
                            schema.objectField("User", "x"),
                            path = null,
                        ) to
                            schema.provider(
                                "fragment ignored on User { z }",
                                "z",
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  viewer {
                    x
                  }
                }
                """.trimIndent(),
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val viewer =
            assertIs<EngineResult.Object>(
                result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "viewer"), emptyMap())).value,
            )

        assertEquals(
            Value.Int.of(10),
            viewer.fetch(Value.ObjectKey.of(world.schema.objectField("User", "x"), emptyMap())).value,
        )
        assertEquals(
            Value.Int.of(2),
            viewer.variableValues.getValue(
                Value.Variable.of("b", world.schema.objectField("User", "x"), path = null),
            ),
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `converts a terminal output list to an input-list variable`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      x: Int!
                      y(values: [Int]): Int!
                      numbers: [Int]
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.emptyFragmentOf("Query")
                    mapOf(
                        schema.field("Query", "x") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      y(values: ${'$'}values)
                                      numbers
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val key =
                                    Value.ObjectKey.of(
                                        schema.objectField("Query", "y"),
                                        mapOf("values" to listOf(2, 3, 5)),
                                    )
                                input.fieldValues.getValue(key)
                            },
                        schema.field("Query", "y") to
                            model.testing.fieldResolverOf(empty) { _, arguments ->
                                val values =
                                    arguments.fieldValues.getValue("values") as Value.InputList
                                Value.Int.of(
                                    values.values.fold(1) { product, value ->
                                        product * (value as Value.Int).intValue
                                    },
                                )
                            },
                        schema.field("Query", "numbers") to
                            model.testing.fieldResolverOf(empty) { _, _ ->
                                Value.OutputList.of(
                                    typeExpr = TypeExpr.Named.of(Schema.IntType),
                                    values =
                                        listOf(
                                            Value.Int.of(2),
                                            Value.Int.of(3),
                                            Value.Int.of(5),
                                        ),
                                )
                            },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        Value.Variable.of("values", schema.field("Query", "x") as Schema.ObjectField, path = null) to
                            schema.provider(
                                "fragment ignored on Query { numbers }",
                                "numbers",
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { x }")

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertEquals(
            Value.Int.of(30),
            result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "x"), emptyMap())).value,
        )
        assertIs<Value.InputList>(
            result.variableValues.getValue(
                Value.Variable.of(
                    "values",
                    world.schema.objectField("Query", "x"),
                    path = null,
                ),
            ),
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `propagates null through an intermediate object path`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Box {
                      value: Int
                    }

                    type Query {
                      x: Int!
                      y(value: Int): Int!
                      box: Box
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.emptyFragmentOf("Query")
                    mapOf(
                        schema.field("Query", "x") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      y(value: ${'$'}value)
                                      box {
                                        value
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val key =
                                    Value.ObjectKey.of(
                                        schema.objectField("Query", "y"),
                                        mapOf("value" to null),
                                    )
                                input.fieldValues.getValue(key)
                            },
                        schema.field("Query", "y") to
                            model.testing.fieldResolverOf(empty) { _, arguments ->
                                require(arguments.fieldValues.getValue("value") == null)
                                Value.Int.of(-1)
                            },
                        schema.field("Query", "box") to
                            model.testing.fieldResolverOf(empty) { _, _ -> null },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        Value.Variable.of("value", schema.field("Query", "x") as Schema.ObjectField, path = null) to
                            schema.provider(
                                "fragment ignored on Query { box { value } }",
                                "box",
                                "value",
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { x }")

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        val variable =
            Value.Variable.of(
                "value",
                world.schema.objectField("Query", "x"),
                path = null,
            )
        assertTrue(variable in result.variableValues)
        assertEquals(null, result.variableValues.getValue(variable))
        assertEquals(
            Value.Int.of(-1),
            result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "x"), emptyMap())).value,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

}
