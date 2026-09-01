package model.registry

import model.requireQueryTypeDef
import model.requireObjectField
import model.Arguments
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.stampedVariables
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StampedObjectPathDefinitionTest {
    @Test
    fun `resolver occurrence stamping distinguishes symbolic variables`() {
        val fragment =
            """
            fragment Result on Query {
              literal: consume(value: 3)
              symbolic: consume(value: ${'$'}seed)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result(seed: Int): Int!
                      consume(value: Int): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(fragment)) { _, _ ->
                                1
                            },
                        schema.requireObjectField("Query", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                1
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                    )
                },
            )
        val result = testWorld.schema.requireObjectField("Query", "result")
        val resolver = testWorld.resolverRegistry.resolver(result)
        val firstPath =
            listOf(
                ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 3)),
            )
        val secondPath =
            listOf(
                ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 4)),
            )

        val compatibilityFragment = resolver.stampVars(firstPath)
        val fullyStampedFragment = resolver.stamp(firstPath)
        val equalFullyStampedFragment = resolver.stamp(firstPath)
        val otherFullyStampedFragment = resolver.stamp(secondPath)

        assertEquals(
            compatibilityFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
            fullyStampedFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
        )
        assertTrue(
            fullyStampedFragment.stampedVariables().all { variable ->
                variable.stamp?.resolverPath == firstPath
            },
        )
        assertEquals(
            fullyStampedFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
            equalFullyStampedFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
        )
        assertNotEquals(
            fullyStampedFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
            otherFullyStampedFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
        )
        assertEquals(
            fullyStampedFragment
                .filter { selection -> selection.key.stampedVariables().isEmpty() }
                .merge(testWorld.schema.requireQueryTypeDef())
                .keys(),
            otherFullyStampedFragment
                .filter { selection -> selection.key.stampedVariables().isEmpty() }
                .merge(testWorld.schema.requireQueryTypeDef())
                .keys(),
        )
    }

    @Test
    fun `stamps definition fragment and provider path at one occurrence`() {
        val source =
            """
            fragment Provider on Query {
              source(value: ${'$'}seed)
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result(seed: Int): Int!
                      source(value: Int): Int!
                      consume(value: Int): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(source)) { _, _ ->
                                1
                            },
                        schema.requireObjectField("Query", "source") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                1
                            },
                        schema.requireObjectField("Query", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                1
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                        Arguments.Variable.of(result, "value") to
                            schema.fromObjectField(source, listOf("source")),
                    )
                },
            )
        val resolver =
            testWorld.resolverRegistry.resolver(
                testWorld.schema.requireObjectField("Query", "result"),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.requireObjectField("Query", "result"),
                mapOf("seed" to 3),
            )
        val sitePath = listOf(resultKey)
        val objectFragment =
            resolver.instantiateObjectFragmentAt(sitePath)
        assertEquals(
            setOf("source", "consume"),
            objectFragment.materializeSelections
                .collect(testWorld.schema.requireQueryTypeDef())
                .responseKeys(),
        )
        assertTrue(
            objectFragment.materializeSelections.all { selection ->
                selection.key !is ObjectEngineResult.VariableKey
            },
        )
        assertEquals(
            1,
            objectFragment.constructionSelections
                .filter { selection -> selection.key is ObjectEngineResult.VariableKey }
                .size,
        )
        val definition = resolver.stampedPathVarDefinitions(sitePath).single()
        val seed =
            Arguments.Variable.of(
                testWorld.schema.requireObjectField("Query", "result"),
                "seed",
            ).stamp(sitePath)

        assertEquals(
            setOf(seed),
            definition.path.single().stampedVariables(),
        )
        assertEquals(
            setOf(seed, definition.variable),
            resolver.stampVars(sitePath).stampedVariables(),
        )
        val marker =
            resolver
                .stampVars(sitePath)
                .filter { selection -> selection.key is ObjectEngineResult.VariableKey }
                .single()
        assertEquals(
            definition.variable,
            assertIs<ObjectEngineResult.VariableKey>(marker.key).variableDefinedByThisKey,
        )
        assertEquals(
            objectFragment.pathVariableDefinitions,
            resolver.stampedPathVarDefinitions(sitePath),
        )
    }

    @Test
    fun `marks every component of a nested provider path`() {
        val fragment =
            """
            fragment Provider on Query {
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
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(fragment)) { _, _ ->
                                1
                            },
                        schema.requireObjectField("Query", "box") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                null
                            },
                        schema.requireObjectField("Query", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                1
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "value") to
                            schema.fromObjectField(fragment, listOf("box", "value")),
                    )
                },
            )
        val resolver =
            testWorld.resolverRegistry.resolver(
                testWorld.schema.requireObjectField("Query", "result"),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )
        val definition = resolver.stampedPathVarDefinitions(listOf(resultKey)).single()
        val markedBox =
            resolver
                .stampVars(listOf(resultKey))
                .filter { selection ->
                    selection.key is ObjectEngineResult.VariableKey &&
                        selection.key.field.name == "box"
                }
                .single()
        val markedValue = markedBox.subselections.single()

        assertEquals(
            definition.variable,
            assertIs<ObjectEngineResult.VariableKey>(markedBox.key).variableDefinedByThisKey,
        )
        assertEquals(
            definition.variable,
            assertIs<ObjectEngineResult.VariableKey>(markedValue.key).variableDefinedByThisKey,
        )
    }

    @Test
    fun `does not mark a repeated prefix that lacks the provider suffix`() {
        val fragment =
            """
            fragment Provider on Query {
              container {
                box { other }
              }
              provider: container {
                complete: box { value }
              }
              consume(value: ${'$'}value)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Box {
                      value: Int
                      other: Int
                    }

                    type Container {
                      box: Box
                    }

                    type Query {
                      result: Int
                      container: Container
                      consume(value: Int): Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(fragment)) { _, _ ->
                                1
                            },
                        schema.requireObjectField("Query", "container") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                null
                            },
                        schema.requireObjectField("Query", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                1
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "value") to
                            schema.fromObjectField(
                                fragment,
                                listOf("provider", "complete", "value"),
                            ),
                    )
                },
            )
        val resolver =
            testWorld.resolverRegistry.resolver(
                testWorld.schema.requireObjectField("Query", "result"),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )
        val markedContainer =
            resolver
                .stampVars(listOf(resultKey))
                .filter { selection ->
                    selection.key is ObjectEngineResult.VariableKey &&
                        selection.key.field.name == "container"
                }.single()
        val markedBoxes =
            markedContainer.subselections.filter { selection ->
                selection.key is ObjectEngineResult.VariableKey &&
                    selection.key.field.name == "box"
            }

        val markedBox = markedBoxes.single()
        assertEquals("value", markedBox.subselections.single().key.field.name)
    }
}
