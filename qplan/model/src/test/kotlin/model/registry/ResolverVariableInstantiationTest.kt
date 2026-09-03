package model.registry

import model.requireQueryTypeDef
import model.requireObjectField
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.instantiatedVariables
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.fromObjectField
import model.testing.testRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResolverVariableInstantiationTest {
    @Test
    fun `resolver occurrence identity distinguishes symbolic variables`() {
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

        val root = testWorld.schema.testRoot()
        val firstFragment =
            resolver.instantiateFragmentsAt(root, firstPath).objectFragment.constructionSelections
        val equalFirstFragment =
            resolver.instantiateFragmentsAt(root, firstPath).objectFragment.constructionSelections
        val otherFragment =
            resolver.instantiateFragmentsAt(root, secondPath).objectFragment.constructionSelections
        val firstOccurrence = ResolverOccurrenceId.at(root, firstPath)

        assertTrue(
            firstFragment.instantiatedVariables().all { variable ->
                variable.instanceId?.resolverOccurrenceId == firstOccurrence
            },
        )
        assertEquals(
            firstFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
            equalFirstFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
        )
        assertNotEquals(
            firstFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
            otherFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
        )
        assertEquals(
            firstFragment
                .filter { selection -> selection.key.instantiatedVariables().isEmpty() }
                .merge(testWorld.schema.requireQueryTypeDef())
                .keys(),
            otherFragment
                .filter { selection -> selection.key.instantiatedVariables().isEmpty() }
                .merge(testWorld.schema.requireQueryTypeDef())
                .keys(),
        )
    }

    @Test
    fun `instantiates definition fragment and provider path at one occurrence`() {
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
            resolver.instantiateFragmentsAt(testWorld.schema.testRoot(), sitePath).objectFragment
        assertEquals(
            setOf("source", "consume"),
            objectFragment.materializeSelections
                .collect(testWorld.schema.requireQueryTypeDef())
                .responseKeys(),
        )
        val definition = objectFragment.pathVariableDefinitions.single()
        val resolverOccurrenceId =
            ResolverOccurrenceId.at(testWorld.schema.testRoot(), sitePath)
        val seed =
            Arguments.Variable.of(
                testWorld.schema.requireObjectField("Query", "result"),
                "seed",
            ).instantiate(resolverOccurrenceId)

        assertEquals(
            setOf(seed),
            definition.path.single().instantiatedVariables(),
        )
        assertEquals(
            setOf(seed, definition.variable),
            objectFragment.constructionSelections.instantiatedVariables(),
        )
        assertEquals(
            objectFragment.pathVariableDefinitions,
            resolver
                .instantiatedFieldPathVariableDefinitions(resolverOccurrenceId)
                .filter { definition -> definition.providerFragment == ProviderFragment.OBJECT },
        )
    }
}
