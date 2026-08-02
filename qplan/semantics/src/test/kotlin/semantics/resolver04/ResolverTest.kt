package semantics.resolver04

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.runBlocking
import model.EngineResult
import model.Schema
import model.TypeExpr
import model.Value
import model.VariableCoordinate
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.arbitrary.resolverTestBatch
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

class ResolverTest {
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
                    val yKey = Value.Key.of(schema.field("Query", "y"), mapOf("b" to 2))
                    mapOf(
                        schema.field("Query", "x") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      y(b: ${'$'}b)
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
                    val variable = Value.Variable.of("b")
                    mapOf(
                        VariableCoordinate.of(
                            schema.field("Query", "x") as Schema.ObjectField,
                            variable,
                        ) to
                            schema.fragmentFrom(
                                """
                                fragment ignored on Query {
                                  z
                                }
                                """.trimIndent(),
                            ).subselections.single(),
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
            result.fetch(Value.Key.of(world.schema.field("Query", "x"), emptyMap())).value,
        )
        assertEquals(Value.Int.of(2), result.variableValues.getValue(Value.Variable.of("b")))
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `seals nested producer demand for a variable provider`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type User {
                      x: Int!
                      y(b: Int!): Int!
                      z: Int!
                    }

                    type Query {
                      viewer: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val user = schema.type("User") as Schema.ObjectType
                    val yKey = Value.Key.of(schema.field("User", "y"), mapOf("b" to 2))
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.objectOf("User") {
                                    "z" setTo 2
                                }
                            },
                        schema.field("User", "x") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on User {
                                      y(b: ${'$'}b)
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val y = input.fieldValues.getValue(yKey) as Value.Int
                                Value.Int.of(y.intValue * 5)
                            },
                        schema.field("User", "y") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, arguments ->
                                val b = arguments.fieldValues.getValue("b") as Value.Int
                                Value.Int.of(b.intValue * 3)
                            },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        VariableCoordinate.of(
                            schema.field("User", "x") as Schema.ObjectField,
                            Value.Variable.of("b"),
                        ) to
                            schema.fragmentFrom(
                                """
                                fragment ignored on User {
                                  z
                                }
                                """.trimIndent(),
                            ).subselections.single(),
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
                result.fetch(Value.Key.of(world.schema.field("Query", "viewer"), emptyMap())).value,
            )

        assertEquals(Value.Int.of(2), viewer.variableValues.getValue(Value.Variable.of("b")))
        assertEquals(
            Value.Int.of(30),
            viewer.fetch(Value.Key.of(world.schema.field("User", "x"), emptyMap())).value,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `merges provider demand with a sibling resolver fragment before applying its producer`() {
        var producerApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      narrow: Int!
                      broad: Int!
                    }

                    type User {
                      result: Int!
                      derived: Int!
                      use(value: Int!): Int!
                      producer: Payload!
                    }

                    type Query {
                      viewer: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val derivedKey = Value.Key.of(schema.field("User", "derived"), emptyMap())
                    val useKey = Value.Key.of(schema.field("User", "use"), mapOf("value" to 2))
                    val producerKey = Value.Key.of(schema.field("User", "producer"), emptyMap())
                    val broadKey = Value.Key.of(schema.field("Payload", "broad"), emptyMap())
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.field("User", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on User {
                                      derived
                                      use(value: ${'$'}value)
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val derived = input.fieldValues.getValue(derivedKey) as Value.Int
                                val use = input.fieldValues.getValue(useKey) as Value.Int
                                Value.Int.of(derived.intValue + use.intValue)
                            },
                        schema.field("User", "derived") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { producer { broad } }",
                                ),
                            ) { input, _ ->
                                val producer =
                                    input.fieldValues.getValue(producerKey) as Value.Object
                                producer.fieldValues.getValue(broadKey)
                            },
                        schema.field("User", "use") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                        schema.field("User", "producer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ ->
                                producerApplications += 1
                                schema.objectOf("Payload") {
                                    "narrow" setTo 2
                                    "broad" setTo 3
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        VariableCoordinate.of(
                            schema.field("User", "result") as Schema.ObjectField,
                            Value.Variable.of("value"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on User { producer { narrow } }",
                            ).subselections.single(),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { viewer { result } }")

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val viewer =
            assertIs<EngineResult.Object>(
                result.fetch(Value.Key.of(world.schema.field("Query", "viewer"), emptyMap())).value,
            )

        assertEquals(1, producerApplications)
        assertEquals(
            Value.Int.of(5),
            viewer.fetch(Value.Key.of(world.schema.field("User", "result"), emptyMap())).value,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `extends a variable argument selection before applying its provider producer`() {
        var producerApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      narrow: Int!
                      broad: Int!
                    }

                    type User {
                      result: Int!
                      middle(value: Int!): Int!
                      final: Int!
                      producer: Payload!
                    }

                    type Query {
                      viewer: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val middleKey = Value.Key.of(schema.field("User", "middle"), mapOf("value" to 2))
                    val finalKey = Value.Key.of(schema.field("User", "final"), emptyMap())
                    val producerKey = Value.Key.of(schema.field("User", "producer"), emptyMap())
                    val broadKey = Value.Key.of(schema.field("Payload", "broad"), emptyMap())
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.field("User", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { middle(value: ${'$'}value) }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(middleKey)
                            },
                        schema.field("User", "middle") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { final }",
                                ),
                            ) { input, arguments ->
                                val final = input.fieldValues.getValue(finalKey) as Value.Int
                                val value =
                                    arguments.fieldValues.getValue("value") as Value.Int
                                Value.Int.of(final.intValue + value.intValue)
                            },
                        schema.field("User", "final") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { producer { broad } }",
                                ),
                            ) { input, _ ->
                                val producer =
                                    input.fieldValues.getValue(producerKey) as Value.Object
                                producer.fieldValues.getValue(broadKey)
                            },
                        schema.field("User", "producer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ ->
                                producerApplications += 1
                                schema.objectOf("Payload") {
                                    "narrow" setTo 2
                                    "broad" setTo 3
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        VariableCoordinate.of(
                            schema.field("User", "result") as Schema.ObjectField,
                            Value.Variable.of("value"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on User { producer { narrow } }",
                            ).subselections.single(),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { viewer { result } }")

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val viewer =
            assertIs<EngineResult.Object>(
                result.fetch(Value.Key.of(world.schema.field("Query", "viewer"), emptyMap())).value,
            )

        assertEquals(1, producerApplications)
        assertEquals(
            Value.Int.of(5),
            viewer.fetch(Value.Key.of(world.schema.field("User", "result"), emptyMap())).value,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `merges operation demand with a sibling transitively required by a provider`() {
        var primaryApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      narrow: Int!
                      shallow: Int!
                      deep: Int!
                    }

                    type User {
                      result: Int!
                      use(value: Int!): Int!
                      primary: Payload!
                      secondary: Payload!
                    }

                    type Query {
                      viewer: User!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val useKey = Value.Key.of(schema.field("User", "use"), mapOf("value" to 1))
                    val primaryKey = Value.Key.of(schema.field("User", "primary"), emptyMap())
                    val shallowKey = Value.Key.of(schema.field("Payload", "shallow"), emptyMap())
                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> schema.objectOf("User") },
                        schema.field("User", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { use(value: ${'$'}value) }",
                                ),
                            ) { input, _ ->
                                input.fieldValues.getValue(useKey)
                            },
                        schema.field("User", "use") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                        schema.field("User", "primary") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("User"),
                            ) { _, _ ->
                                primaryApplications += 1
                                schema.objectOf("Payload") {
                                    "shallow" setTo 1
                                    "deep" setTo 3
                                }
                            },
                        schema.field("User", "secondary") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on User { primary { shallow } }",
                                ),
                            ) { input, _ ->
                                val primary =
                                    input.fieldValues.getValue(primaryKey) as Value.Object
                                val shallow = primary.fieldValues.getValue(shallowKey)
                                schema.objectOf("Payload") {
                                    "narrow" setTo shallow
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        VariableCoordinate.of(
                            schema.field("User", "result") as Schema.ObjectField,
                            Value.Variable.of("value"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on User { secondary { narrow } }",
                            ).subselections.single(),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                "fragment ignored on Query { viewer { primary { deep } result } }",
            )

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }
        val viewer =
            assertIs<EngineResult.Object>(
                result.fetch(Value.Key.of(world.schema.field("Query", "viewer"), emptyMap())).value,
            )
        val primary =
            assertIs<EngineResult.Object>(
                viewer.fetch(Value.Key.of(world.schema.field("User", "primary"), emptyMap())).value,
            )

        assertEquals(1, primaryApplications)
        assertEquals(
            Value.Int.of(3),
            primary.fetch(Value.Key.of(world.schema.field("Payload", "deep"), emptyMap())).value,
        )
        assertEquals(
            Value.Int.of(1),
            viewer.fetch(Value.Key.of(world.schema.field("User", "result"), emptyMap())).value,
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
                                    "fragment ignored on Query { y(b: ${'$'}b) }",
                                ),
                            ) { input, _ ->
                                val key = Value.Key.of(schema.field("Query", "y"), mapOf("b" to 6))
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
                        VariableCoordinate.of(owner, Value.Variable.of("b")) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { z(c: ${'$'}c) }",
                            ).subselections.single(),
                        VariableCoordinate.of(owner, Value.Variable.of("c")) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { raw }",
                            ).subselections.single(),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { x }")

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertEquals(Value.Int.of(6), result.variableValues.getValue(Value.Variable.of("b")))
        assertEquals(Value.Int.of(2), result.variableValues.getValue(Value.Variable.of("c")))
        assertEquals(
            Value.Int.of(210),
            result.fetch(Value.Key.of(world.schema.field("Query", "x"), emptyMap())).value,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `merges field demand when distinct variables resolve to the same value`() {
        var yApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      x: Int!
                      y(value: Int!): Int!
                      raw: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val yKey = Value.Key.of(schema.field("Query", "y"), mapOf("value" to 2))
                    mapOf(
                        schema.field("Query", "x") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      y(value: ${'$'}first)
                                      y(value: ${'$'}second)
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                require(input.fieldValues.keys == setOf(yKey))
                                input.fieldValues.getValue(yKey)
                            },
                        schema.field("Query", "y") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, arguments ->
                                yApplications += 1
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                        schema.field("Query", "raw") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> Value.Int.of(2) },
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.field("Query", "x") as Schema.ObjectField
                    val provider =
                        schema.fragmentFrom(
                            "fragment ignored on Query { raw }",
                        ).subselections.single()
                    mapOf(
                        VariableCoordinate.of(owner, Value.Variable.of("first")) to provider,
                        VariableCoordinate.of(owner, Value.Variable.of("second")) to provider,
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { x }")

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertEquals(1, yApplications)
        assertEquals(
            mapOf(
                Value.Variable.of("first") to Value.Int.of(2),
                Value.Variable.of("second") to Value.Int.of(2),
            ),
            result.variableValues,
        )
        assertEquals(
            Value.Int.of(2),
            result.fetch(Value.Key.of(world.schema.field("Query", "x"), emptyMap())).value,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `merges symbolic demand when variables from different resolvers converge`() {
        var sourceApplications = 0
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      narrow: Int!
                      broad: Int!
                    }

                    type Query {
                      result: Int!
                      middle: Int!
                      source(value: Int!): Payload!
                      raw: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val sourceKey =
                        Value.Key.of(schema.field("Query", "source"), mapOf("value" to 1))
                    val narrowKey = Value.Key.of(schema.field("Payload", "narrow"), emptyMap())
                    val broadKey = Value.Key.of(schema.field("Payload", "broad"), emptyMap())
                    mapOf(
                        schema.field("Query", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      source(value: ${'$'}late) {
                                        broad
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val source = input.fieldValues.getValue(sourceKey) as Value.Object
                                source.fieldValues.getValue(broadKey)
                            },
                        schema.field("Query", "middle") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      source(value: ${'$'}early) {
                                        narrow
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val source = input.fieldValues.getValue(sourceKey) as Value.Object
                                source.fieldValues.getValue(narrowKey)
                            },
                        schema.field("Query", "source") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, arguments ->
                                sourceApplications += 1
                                assertEquals(Value.Int.of(1), arguments.fieldValues["value"])
                                schema.objectOf("Payload") {
                                    "narrow" setTo 1
                                    "broad" setTo 3
                                }
                            },
                        schema.field("Query", "raw") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> Value.Int.of(1) },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        VariableCoordinate.of(
                            schema.field("Query", "result") as Schema.ObjectField,
                            Value.Variable.of("late"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { middle }",
                            ).subselections.single(),
                        VariableCoordinate.of(
                            schema.field("Query", "middle") as Schema.ObjectField,
                            Value.Variable.of("early"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { raw }",
                            ).subselections.single(),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { result }")

        val resolved =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertEquals(1, sourceApplications)
        assertEquals(
            mapOf(
                Value.Variable.of("early") to Value.Int.of(1),
                Value.Variable.of("late") to Value.Int.of(1),
            ),
            resolved.variableValues,
        )
        assertEquals(
            Value.Int.of(3),
            resolved.fetch(Value.Key.of(world.schema.field("Query", "result"), emptyMap())).value,
        )
        assertTrue(context(world) { resolved.correctResolution(fragment) })
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
                    val childKey = Value.Key.of(schema.field("User", "child"), emptyMap())
                    val yKey = Value.Key.of(schema.field("Child", "y"), mapOf("b" to 2))
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
                        VariableCoordinate.of(
                            schema.field("User", "x") as Schema.ObjectField,
                            Value.Variable.of("b"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on User { z }",
                            ).subselections.single(),
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
                result.fetch(Value.Key.of(world.schema.field("Query", "viewer"), emptyMap())).value,
            )

        assertEquals(
            Value.Int.of(10),
            viewer.fetch(Value.Key.of(world.schema.field("User", "x"), emptyMap())).value,
        )
        assertEquals(Value.Int.of(2), viewer.variableValues.getValue(Value.Variable.of("b")))
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
                                    "fragment ignored on Query { y(values: ${'$'}values) }",
                                ),
                            ) { input, _ ->
                                val key =
                                    Value.Key.of(
                                        schema.field("Query", "y"),
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
                        VariableCoordinate.of(
                            schema.field("Query", "x") as Schema.ObjectField,
                            Value.Variable.of("values"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { numbers }",
                            ).subselections.single(),
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
            result.fetch(Value.Key.of(world.schema.field("Query", "x"), emptyMap())).value,
        )
        assertIs<Value.InputList>(
            result.variableValues.getValue(Value.Variable.of("values")),
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
                                    "fragment ignored on Query { y(value: ${'$'}value) }",
                                ),
                            ) { input, _ ->
                                val key =
                                    Value.Key.of(
                                        schema.field("Query", "y"),
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
                        VariableCoordinate.of(
                            schema.field("Query", "x") as Schema.ObjectField,
                            Value.Variable.of("value"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { box { value } }",
                            ).subselections.single(),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom("fragment ignored on Query { x }")

        val result =
            context(world) {
                world.objectOf("Query").resolve(fragment.subselections)
            }

        assertTrue(Value.Variable.of("value") in result.variableValues)
        assertEquals(null, result.variableValues.getValue(Value.Variable.of("value")))
        assertEquals(
            Value.Int.of(-1),
            result.fetch(Value.Key.of(world.schema.field("Query", "x"), emptyMap())).value,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `arbitrary valid worlds resolve correctly`(): Unit =
        runBlocking {
            var activatedVariableCases = 0
            val counts =
                TestCaseCount(
                    schemas = 20,
                    registriesPerSchema = 3,
                    queriesPerSchema = 5,
                )
            val config =
                Config.default +
                    (SchemaObjectCount to 3..5) +
                    (ObjectFieldCount to 3..5) +
                    (FieldArgumentWeight to 0.7) +
                    (ExplicitFieldResolverWeight to 0.6) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 0.9) +
                    (ResolverVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0)

            checkResolverTestCases(counts, config) { testWorld, testCase ->
                val result = generatedResolution(testWorld, testCase.query.source)
                if (result.hasVariableValues()) {
                    activatedVariableCases += 1
                }
            }
            assertTrue(
                activatedVariableCases > 0,
                "Resolver04 property activated no resolver variables",
            )
        }

    @Test
    fun `generated property detects missing transitive demand closure`() {
        val counts =
            TestCaseCount(
                schemas = 2,
                registriesPerSchema = 3,
                queriesPerSchema = 5,
            )
        val config =
            Config.default +
                (ResolverFragmentsEnabled to true)
        val random = RandomSource.seeded(-2028282154048352130L)
        val batches =
            List(counts.schemas) {
                Arb.resolverTestBatch(counts, config).next(random)
            }
        var passingCases = 0
        var failingCases = 0

        batches.forEach { batch ->
            batch.registries.forEach { registry ->
                val ordinaryWorld = registry.world(batch.schema)
                val mutantWorld =
                    registry.world(
                        schema = batch.schema,
                        noTransitiveDemand = true,
                    )
                batch.queries.forEach { query ->
                    assertTrue(
                        generatedResolutionIsCorrect(ordinaryWorld, query.source),
                        "The mutation-control corpus must pass ordinary resolver04",
                    )
                    val correct =
                        runCatching {
                            generatedResolutionIsCorrect(mutantWorld, query.source)
                        }.getOrDefault(false)
                    if (correct) passingCases += 1 else failingCases += 1
                }
            }
        }

        assertTrue(passingCases > 0, "The mutant should not reject every generated case")
        assertTrue(failingCases > 0, "Generated cases did not detect the transitive-demand mutant")
    }

    @Test
    fun `resolves typename as the concrete object type`() {
        val world = TestWorld.fromSDL(FLAT_SCHEMA_SDL).assumptions
        val schema = world.schema
        val (fragment, selections) =
            context(world) {
                parsedFragment(
                    """
                    fragment ignored on Query {
                      __typename
                    }
                    """.trimIndent(),
                )
            }

        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        val typeName =
            assertIs<Value.String>(
                result.fetch(schema.key(schema.query, "__typename")).value,
            )
        assertEquals("Query", typeName.stringValue)
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `closes and orders transitive sibling resolver demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = FLAT_SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val user = schema.objectType("User")
                    val firstNameKey = schema.key(user, "firstName")
                    val lastNameKey = schema.key(user, "lastName")
                    val displayNameKey = schema.key(user, "displayName")

                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { input, _ ->
                                    require(input.fieldValues.isEmpty())
                                    schema.objectOf("User") {
                                        "firstName" setTo "Ada"
                                        "lastName" setTo "Lovelace"
                                    }
                                },
                            ),
                        schema.field("User", "displayName") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          firstName
                                          lastName
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    require(
                                        input.fieldValues.keys ==
                                            setOf(firstNameKey, lastNameKey),
                                    )
                                    val firstName =
                                        input.fieldValues.getValue(firstNameKey) as Value.String
                                    val lastName =
                                        input.fieldValues.getValue(lastNameKey) as Value.String
                                    Value.String.of(
                                        "${firstName.stringValue} ${lastName.stringValue}",
                                    )
                                },
                            ),
                        schema.field("User", "greeting") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          displayName
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    require(input.fieldValues.keys == setOf(displayNameKey))
                                    val displayName =
                                        input.fieldValues.getValue(displayNameKey) as Value.String
                                    Value.String.of("Hello, ${displayName.stringValue}")
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val (fragment, selections) =
            context(world) {
                parsedFragment(
                    """
                    fragment ignored on Query {
                      viewer {
                        greeting
                      }
                    }
                    """.trimIndent(),
                )
            }

        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        val viewer =
            assertIs<EngineResult.Object>(
                result.fetch(schema.key(schema.query, "viewer")).value,
            )
        assertEquals(
            setOf("firstName", "lastName", "displayName", "greeting"),
            viewer.keys.map { key -> key.field.fieldName }.toSet(),
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `resolves descendant demand before its consuming sibling`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = NESTED_SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val user = schema.objectType("User")
                    val profile = schema.objectType("Profile")
                    val profileKey = schema.key(user, "profile")
                    val rawKey = schema.key(profile, "raw")
                    val renderedKey = schema.key(profile, "rendered")

                    mapOf(
                        schema.field("Query", "viewer") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    schema.objectOf("User") {
                                        "profile" setTo
                                            objectOf("Profile") {
                                                "raw" setTo "engineer"
                                            }
                                    }
                                },
                            ),
                        schema.field("Profile", "rendered") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Profile {
                                          raw
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    require(input.fieldValues.keys == setOf(rawKey))
                                    val raw =
                                        input.fieldValues.getValue(rawKey) as Value.String
                                    Value.String.of("Role: ${raw.stringValue}")
                                },
                            ),
                        schema.field("User", "message") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on User {
                                          profile {
                                            rendered
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    require(input.fieldValues.keys == setOf(profileKey))
                                    val profileInput =
                                        input.fieldValues.getValue(profileKey) as Value.Object
                                    require(
                                        profileInput.fieldValues.keys == setOf(renderedKey),
                                    )
                                    val rendered =
                                        profileInput.fieldValues.getValue(renderedKey) as Value.String
                                    Value.String.of(rendered.stringValue)
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val (fragment, selections) =
            context(world) {
                parsedFragment(
                    """
                    fragment ignored on Query {
                      viewer {
                        message
                      }
                    }
                    """.trimIndent(),
                )
            }

        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        val viewer =
            assertIs<EngineResult.Object>(
                result.fetch(schema.key(schema.query, "viewer")).value,
            )
        val profile =
            assertIs<EngineResult.Object>(
                viewer.fetch(schema.key(schema.objectType("User"), "profile")).value,
            )
        assertEquals(
            setOf("raw", "rendered"),
            profile.keys.map { key -> key.field.fieldName }.toSet(),
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `resolves recursive demand introduced by an object fragment`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = RECURSIVE_SCHEMA_SDL,
                fieldResolvers = { schema ->
                    val chain = schema.objectType("Chain")
                    val nextKey = schema.key(chain, "next")
                    val labelKey = schema.key(chain, "label")
                    mapOf(
                        schema.field("Query", "chain") to
                            model.testing.fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                function = { _, _ ->
                                    schema.objectOf("Chain") {
                                        "label" setTo "first"
                                        "next" setTo
                                            objectOf("Chain") {
                                                "label" setTo "second"
                                                "next" setTo null
                                            }
                                    }
                                },
                            ),
                        schema.field("Chain", "computed") to
                            model.testing.fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Chain {
                                          next {
                                            label
                                          }
                                        }
                                        """.trimIndent(),
                                    ),
                                function = { input, _ ->
                                    val next =
                                        input.fieldValues.getValue(nextKey) as Value.Object
                                    val label =
                                        next.fieldValues.getValue(labelKey) as Value.String
                                    Value.String.of(label.stringValue)
                                },
                            ),
                    )
                },
            )
        val world = testWorld.assumptions
        val schema = world.schema
        val (fragment, selections) =
            context(world) {
                parsedFragment(
                    """
                    fragment ignored on Query {
                      chain {
                        computed
                      }
                    }
                    """.trimIndent(),
                )
            }

        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }

        val chain =
            assertIs<EngineResult.Object>(
                result.fetch(schema.key(schema.query, "chain")).value,
            )
        val next =
            assertIs<EngineResult.Object>(
                chain.fetch(schema.key(schema.objectType("Chain"), "next")).value,
            )
        assertEquals(setOf("label"), next.keys.map { it.field.fieldName }.toSet())
        assertEquals(
            "second",
            assertIs<Value.String>(
                chain.fetch(schema.key(schema.objectType("Chain"), "computed")).value,
            ).stringValue,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    context(world: model.Assumptions)
    private fun parsedFragment(source: String) =
        world.fragmentFrom(source).let { it to it.subselections }

    private fun generatedResolutionIsCorrect(
        testWorld: TestWorld,
        querySource: String,
    ): Boolean {
        generatedResolution(testWorld, querySource)
        return true
    }

    private fun generatedResolution(
        testWorld: TestWorld,
        querySource: String,
    ): EngineResult.Object {
        val world = testWorld.assumptions
        val fragment = world.fragmentFrom(querySource)
        val selections = fragment.subselections
        val result =
            context(world) {
                world.objectOf("Query").resolve(selections)
            }
        context(world) {
            val checks =
                mapOf(
                    "rootedAndWellTyped" to result.rootedAndWellTyped(fragment),
                    "conformsToFragment" to result.conformsToFragment(fragment),
                    "isClosedUnderResolverDemand" to result.isClosedUnderResolverDemand(),
                    "conformsToVariables" to result.conformsToVariables(),
                    "conformsToResolvers" to result.conformsToResolvers(),
                    "conformsToTypename" to result.conformsToTypename(),
                )
            check(checks.values.all { it }) {
                "Incorrect generated resolution: " +
                    checks.filterValues { correct -> !correct }.keys.joinToString()
            }
        }
        return result
    }

    private fun EngineResult?.hasVariableValues(): Boolean =
        when (this) {
            is EngineResult.Object ->
                variableValues.isNotEmpty() ||
                    keys.any { key -> fetch(key).value.hasVariableValues() }
            is EngineResult.List -> any { cell -> cell.value.hasVariableValues() }
            null,
            Value.Error,
            is Value.Simple,
            -> false
        }

    private companion object {
        val FLAT_SCHEMA_SDL =
            """
            type User {
              firstName: String!
              lastName: String!
              displayName: String!
              greeting: String!
            }

            type Query {
              viewer: User!
            }
            """.trimIndent()

        val NESTED_SCHEMA_SDL =
            """
            type Profile {
              raw: String!
              rendered: String!
            }

            type User {
              profile: Profile!
              message: String!
            }

            type Query {
              viewer: User!
            }
            """.trimIndent()

        val RECURSIVE_SCHEMA_SDL =
            """
            type Chain {
              label: String!
              next: Chain
              computed: String!
            }

            type Query {
              chain: Chain!
            }
            """.trimIndent()
    }
}

private fun Schema.objectType(typeName: String): Schema.ObjectType =
    type(typeName) as Schema.ObjectType

private fun Schema.key(
    type: Schema.ObjectType,
    fieldName: String,
): Value.Key =
    Value.Key.of(
        field = field(type.typeName, fieldName),
        arguments = emptyMap(),
    )
