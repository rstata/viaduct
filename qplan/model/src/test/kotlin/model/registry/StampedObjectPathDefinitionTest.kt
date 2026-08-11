package model.registry

import model.Value
import model.emptyFragmentOf
import model.fragmentFrom
import model.stampedVariables
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StampedObjectPathDefinitionTest {
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
            Value.GroundKey.of(
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
            resolver.stampedObjectFragment(sitePath).stampedVariables(),
        )
        val marker =
            resolver
                .stampedObjectFragment(sitePath)
                .filter { selection -> selection.key is Value.VariableKey }
                .single()
        assertEquals(
            definition.variable,
            assertIs<Value.VariableKey>(marker.key).variableDefinedByThisKey,
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
            Value.GroundKey.of(
                testWorld.schema.objectField("Query", "result"),
                emptyMap(),
            )
        val definition = resolver.stampedPathVarDefinitions(listOf(resultKey)).single()
        val markedBox =
            resolver
                .stampedObjectFragment(listOf(resultKey))
                .filter { selection ->
                    selection.key is Value.VariableKey &&
                        selection.key.field.fieldName == "box"
                }
                .single()
        val markedValue = markedBox.subselections.single()

        assertEquals(
            definition.variable,
            assertIs<Value.VariableKey>(markedBox.key).variableDefinedByThisKey,
        )
        assertEquals(
            definition.variable,
            assertIs<Value.VariableKey>(markedValue.key).variableDefinedByThisKey,
        )
    }
}
