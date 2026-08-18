package model.registry

import model.ListEngineResult
import model.ObjectEngineResult

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.localizeTopLevelSelectionStamps
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

class StampedObjectPathDefinitionTest {
    @Test
    fun `selection stamping distinguishes variables while ground arguments remain compatible`() {
        val fragment =
            """
            fragment Result on Query {
              consume(value: 3)
              consume(value: ${'$'}seed)
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
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(fragment)) { _, _ ->
                                Value.Int.of(1)
                            },
                        schema.objectField("Query", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                    )
                },
            )
        val result = testWorld.schema.objectField("Query", "result")
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

        compatibilityFragment.forEach { selection ->
            assertFalse(selection.key is ObjectEngineResult.Key.Stamped)
        }
        fullyStampedFragment.forEach { selection ->
            if (selection.key.stampedVariables().isEmpty()) {
                assertIs<Value.Arguments>(selection.key.arguments)
                assertFalse(selection.key is ObjectEngineResult.Key.Stamped)
            } else {
                val stampedKey = assertIs<ObjectEngineResult.Key.Stamped>(selection.key)
                assertEquals(
                    setOf(stampedKey.selectionStamp),
                    selection.key
                        .stampedVariables()
                        .filterIsInstance<Value.Variable.SelectionStamped>()
                        .mapTo(linkedSetOf()) { variable -> variable.selectionStamp },
                )
            }
        }
        assertEquals(
            fullyStampedFragment.merge(testWorld.schema.query).keys(),
            equalFullyStampedFragment.merge(testWorld.schema.query).keys(),
        )
        assertNotEquals(
            fullyStampedFragment.merge(testWorld.schema.query).keys(),
            otherFullyStampedFragment.merge(testWorld.schema.query).keys(),
        )
        assertEquals(
            fullyStampedFragment
                .filter { selection -> selection.key.stampedVariables().isEmpty() }
                .merge(testWorld.schema.query)
                .keys(),
            otherFullyStampedFragment
                .filter { selection -> selection.key.stampedVariables().isEmpty() }
                .merge(testWorld.schema.query)
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
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(source)) { _, _ ->
                                Value.Int.of(1)
                            },
                        schema.objectField("Query", "source") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                        schema.objectField("Query", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(source, listOf("source")),
                    )
                },
            )
        val resolver =
            testWorld.resolverRegistry.resolver(
                testWorld.schema.objectField("Query", "result"),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.objectField("Query", "result"),
                mapOf("seed" to 3),
            )
        val sitePath = listOf(resultKey)
        val definition = resolver.stampedPathVarDefinitions(sitePath).single()
        val seed =
            Value.Variable.of(
                testWorld.schema.objectField("Query", "result"),
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
        val fullyStampedMarker =
            resolver
                .stamp(sitePath)
                .filter { selection -> selection.key is ObjectEngineResult.VariableKey }
                .single()
        val selectionStampedValue =
            resolver
                .selectionStampedVariableDefinitions(sitePath)
                .single { stampedDefinition ->
                    stampedDefinition.variable.variableName == "value"
                }.variable
        val fullyStampedMarkerKey =
            assertIs<ObjectEngineResult.VariableKey.Stamped>(fullyStampedMarker.key)
        assertEquals(
            selectionStampedValue,
            assertIs<ObjectEngineResult.VariableKey>(
                fullyStampedMarker.key,
            ).variableDefinedByThisKey,
        )

        val localizationPath = listOf(ListEngineResult.Index.of(2))
        val localizedMarker =
            resolver
                .stamp(sitePath)
                .localizeTopLevelSelectionStamps(localizationPath)
                .filter { selection -> selection.key is ObjectEngineResult.VariableKey }
                .single()
        val localizedMarkerKey =
            assertIs<ObjectEngineResult.VariableKey.Stamped>(localizedMarker.key)
        val localizedMarkerVariable =
            assertIs<Value.Variable.SelectionStamped>(
                localizedMarkerKey.variableDefinedByThisKey,
            )

        assertEquals(
            fullyStampedMarkerKey.selectionStamp.resolverPath + localizationPath,
            localizedMarkerKey.selectionStamp.resolverPath,
        )
        assertEquals(
            selectionStampedValue.selectionStamp.resolverPath + localizationPath,
            localizedMarkerVariable.selectionStamp.resolverPath,
        )
        assertEquals(
            setOf(localizedMarkerKey.selectionStamp),
            localizedMarkerKey.arguments
                .stampedVariables()
                .filterIsInstance<Value.Variable.SelectionStamped>()
                .mapTo(linkedSetOf()) { variable -> variable.selectionStamp },
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
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(fragment)) { _, _ ->
                                Value.Int.of(1)
                            },
                        schema.objectField("Query", "box") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                null
                            },
                        schema.objectField("Query", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(fragment, listOf("box", "value")),
                    )
                },
            )
        val resolver =
            testWorld.resolverRegistry.resolver(
                testWorld.schema.objectField("Query", "result"),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.objectField("Query", "result"),
                emptyMap(),
            )
        val definition = resolver.stampedPathVarDefinitions(listOf(resultKey)).single()
        val markedBox =
            resolver
                .stampVars(listOf(resultKey))
                .filter { selection ->
                    selection.key is ObjectEngineResult.VariableKey &&
                        selection.key.field.fieldName == "box"
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
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(fragment)) { _, _ ->
                                Value.Int.of(1)
                            },
                        schema.objectField("Query", "container") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                null
                            },
                        schema.objectField("Query", "consume") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                Value.Int.of(1)
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Value.Variable.of(result, "value") to
                            schema.fromObjectField(
                                fragment,
                                listOf("provider", "complete", "value"),
                            ),
                    )
                },
            )
        val resolver =
            testWorld.resolverRegistry.resolver(
                testWorld.schema.objectField("Query", "result"),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.objectField("Query", "result"),
                emptyMap(),
            )
        val markedContainer =
            resolver
                .stampVars(listOf(resultKey))
                .filter { selection ->
                    selection.key is ObjectEngineResult.VariableKey &&
                        selection.key.field.fieldName == "container"
                }.single()
        val markedBoxes =
            markedContainer.subselections.filter { selection ->
                selection.key is ObjectEngineResult.VariableKey &&
                    selection.key.field.fieldName == "box"
            }

        val markedBox = markedBoxes.single()
        assertEquals("value", markedBox.subselections.single().key.field.fieldName)
    }
}
