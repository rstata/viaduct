package semantics.resolver04

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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Symbolic and concrete demand aggregation before selective producer application.
 *
 * Keep provider merging, convergence, exact-occurrence, and shared-producer sealing cases here.
 */
class ResolverDemandSealingTest {
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
                    val yKey = Value.ObjectKey.of(schema.objectField("User", "y"), mapOf("b" to 2))
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
                                      z
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
                result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "viewer"), emptyMap())).value,
            )

        assertEquals(Value.Int.of(2), viewer.variableValues.getValue(Value.Variable.of("b")))
        assertEquals(
            Value.Int.of(30),
            viewer.fetch(Value.ObjectKey.of(world.schema.objectField("User", "x"), emptyMap())).value,
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
                    val derivedKey = Value.ObjectKey.of(schema.objectField("User", "derived"), emptyMap())
                    val useKey = Value.ObjectKey.of(schema.objectField("User", "use"), mapOf("value" to 2))
                    val producerKey = Value.ObjectKey.of(schema.objectField("User", "producer"), emptyMap())
                    val broadKey = Value.ObjectKey.of(schema.objectField("Payload", "broad"), emptyMap())
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
                                      producer {
                                        narrow
                                      }
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
                result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "viewer"), emptyMap())).value,
            )

        assertEquals(1, producerApplications)
        assertEquals(
            Value.Int.of(5),
            viewer.fetch(Value.ObjectKey.of(world.schema.objectField("User", "result"), emptyMap())).value,
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
                    val middleKey = Value.ObjectKey.of(schema.objectField("User", "middle"), mapOf("value" to 2))
                    val finalKey = Value.ObjectKey.of(schema.objectField("User", "final"), emptyMap())
                    val producerKey = Value.ObjectKey.of(schema.objectField("User", "producer"), emptyMap())
                    val broadKey = Value.ObjectKey.of(schema.objectField("Payload", "broad"), emptyMap())
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
                                      middle(value: ${'$'}value)
                                      producer {
                                        narrow
                                      }
                                    }
                                    """.trimIndent(),
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
                result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "viewer"), emptyMap())).value,
            )

        assertEquals(1, producerApplications)
        assertEquals(
            Value.Int.of(5),
            viewer.fetch(Value.ObjectKey.of(world.schema.objectField("User", "result"), emptyMap())).value,
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
                    val useKey = Value.ObjectKey.of(schema.objectField("User", "use"), mapOf("value" to 1))
                    val primaryKey = Value.ObjectKey.of(schema.objectField("User", "primary"), emptyMap())
                    val shallowKey = Value.ObjectKey.of(schema.objectField("Payload", "shallow"), emptyMap())
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
                                      use(value: ${'$'}value)
                                      secondary {
                                        narrow
                                      }
                                    }
                                    """.trimIndent(),
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
                result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "viewer"), emptyMap())).value,
            )
        val primary =
            assertIs<EngineResult.Object>(
                viewer.fetch(Value.ObjectKey.of(world.schema.objectField("User", "primary"), emptyMap())).value,
            )

        assertEquals(1, primaryApplications)
        assertEquals(
            Value.Int.of(3),
            primary.fetch(Value.ObjectKey.of(world.schema.objectField("Payload", "deep"), emptyMap())).value,
        )
        assertEquals(
            Value.Int.of(1),
            viewer.fetch(Value.ObjectKey.of(world.schema.objectField("User", "result"), emptyMap())).value,
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
                    val yKey = Value.ObjectKey.of(schema.objectField("Query", "y"), mapOf("value" to 2))
                    val rawKey = Value.ObjectKey.of(schema.objectField("Query", "raw"), emptyMap())
                    mapOf(
                        schema.field("Query", "x") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      y(value: ${'$'}first)
                                      y(value: ${'$'}second)
                                      raw
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                require(input.fieldValues.keys == setOf(yKey, rawKey))
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
            result.fetch(Value.ObjectKey.of(world.schema.objectField("Query", "x"), emptyMap())).value,
        )
        assertTrue(context(world) { result.correctResolution(fragment) })
    }

    @Test
    fun `rejects contradictory provider and resolver branch orders`() {
        var sourceApplications = 0
        val failure =
            assertFailsWith<IllegalArgumentException> {
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
                        Value.ObjectKey.of(schema.objectField("Query", "source"), mapOf("value" to 1))
                    val narrowKey = Value.ObjectKey.of(schema.objectField("Payload", "narrow"), emptyMap())
                    val broadKey = Value.ObjectKey.of(schema.objectField("Payload", "broad"), emptyMap())
                    mapOf(
                        schema.field("Query", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      source(value: ${'$'}late) {
                                        broad
                                      }
                                      middle
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
                                      raw
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
            }

        assertTrue(failure.message!!.contains("middle -> source -> middle"))
        assertEquals(0, sourceApplications)
    }

    @Test
    fun `rejects a cross-variable branch cycle around a shared producer`() {
        var sourceApplications = 0
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      narrow: Int!
                      broad: Int!
                      computed(value: Int!): Int!
                    }

                    type Query {
                      result: Int!
                      source: Payload!
                      helper(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val sourceKey = Value.ObjectKey.of(schema.objectField("Query", "source"), emptyMap())
                    val broadKey = Value.ObjectKey.of(schema.objectField("Payload", "broad"), emptyMap())
                    val computedKey =
                        Value.ObjectKey.of(schema.objectField("Payload", "computed"), mapOf("value" to 2))
                    mapOf(
                        schema.field("Query", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      source {
                                        broad
                                        computed(value: ${'$'}later)
                                      }
                                      helper(value: ${'$'}early)
                                      source {
                                        narrow
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val source = input.fieldValues.getValue(sourceKey) as Value.Object
                                val broad = source.fieldValues.getValue(broadKey) as Value.Int
                                val computed = source.fieldValues.getValue(computedKey) as Value.Int
                                Value.Int.of(broad.intValue + computed.intValue)
                            },
                        schema.field("Query", "source") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                sourceApplications += 1
                                schema.objectOf("Payload") {
                                    "narrow" setTo 1
                                    "broad" setTo 3
                                }
                            },
                        schema.field("Query", "helper") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, arguments ->
                                val value =
                                    arguments.fieldValues.getValue("value") as Value.Int
                                Value.Int.of(value.intValue + 1)
                            },
                        schema.field("Payload", "computed") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Payload"),
                            ) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Value.Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val owner = schema.field("Query", "result") as Schema.ObjectField
                    mapOf(
                        VariableCoordinate.of(owner, Value.Variable.of("later")) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { helper(value: ${'$'}early) }",
                            ).subselections.single(),
                        VariableCoordinate.of(owner, Value.Variable.of("early")) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { source { narrow } }",
                            ).subselections.single(),
                    )
                    },
                )
            }

        assertTrue(failure.message!!.contains("helper -> source -> helper"))
        assertEquals(0, sourceApplications)
    }

    @Test
    fun `rejects argument-distinct provider and use occurrences on one branch`() {
        var sourceApplications = 0
        val failure =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Payload {
                      narrow: Int!
                      broad: Int!
                    }

                    type Query {
                      result: Int!
                      source(k: Int!): Payload!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val sourceKey =
                        Value.ObjectKey.of(schema.objectField("Query", "source"), mapOf("k" to 2))
                    val broadKey = Value.ObjectKey.of(schema.objectField("Payload", "broad"), emptyMap())
                    mapOf(
                        schema.field("Query", "result") to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Query {
                                      source(k: ${'$'}k) {
                                        broad
                                      }
                                      source(k: 1) {
                                        narrow
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                val source = input.fieldValues.getValue(sourceKey) as Value.Object
                                source.fieldValues.getValue(broadKey)
                            },
                        schema.field("Query", "source") to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, arguments ->
                                sourceApplications += 1
                                val k = arguments.fieldValues.getValue("k") as Value.Int
                                when (k.intValue) {
                                    1 ->
                                        schema.objectOf("Payload") {
                                            "narrow" setTo 2
                                        }
                                    2 ->
                                        schema.objectOf("Payload") {
                                            "broad" setTo 7
                                        }
                                    else -> error("Unexpected source argument ${k.intValue}")
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    mapOf(
                        VariableCoordinate.of(
                            schema.field("Query", "result") as Schema.ObjectField,
                            Value.Variable.of("k"),
                        ) to
                            schema.fragmentFrom(
                                "fragment ignored on Query { source(k: 1) { narrow } }",
                            ).subselections.single(),
                    )
                    },
                )
            }

        assertTrue(failure.message!!.contains("source -> source"))
        assertEquals(0, sourceApplications)
    }

}
