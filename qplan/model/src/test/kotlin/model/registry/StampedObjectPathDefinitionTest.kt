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
    }
}
