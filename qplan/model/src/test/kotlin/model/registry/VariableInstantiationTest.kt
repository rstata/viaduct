package model.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import model.Arguments
import model.ListEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireArg
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.usedVariables

class VariableInstantiationTest {
    private val world =
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
                        fieldResolverOf(
                            schema.fragmentFrom(
                                """
                                fragment Result on Query {
                                  first: consume(value: ${'$'}seed)
                                  second: consume(value: ${'$'}seed)
                                }
                                """.trimIndent(),
                            ),
                        ) { _, _ -> 1 },
                    schema.requireObjectField("Query", "consume") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 1 },
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

    @Test
    fun `one resolver occurrence owns every use of a variable`() {
        val resolver =
            world.resolverRegistry.resolver(
                world.schema.requireObjectField("Query", "result"),
            )
        val occurrence =
            ResolverOccurrenceId.at(
                listOf(ListEngineResult.Index.of(1)),
            )

        val fragment = resolver.instantiateObjectFragment(occurrence)
        val definition = fragment.variableDefinitions.single()
        val variables = fragment.constructionSelections.usedVariables()

        assertEquals(1, variables.size)
        assertEquals(definition.variable, variables.single())
        assertEquals(definition.variable.instanceId, variables.single().instanceId)
    }

    @Test
    fun `different resolver occurrences own different variable instances`() {
        val resolver =
            world.resolverRegistry.resolver(
                world.schema.requireObjectField("Query", "result"),
            )

        val first =
            resolver
                .instantiateObjectFragmentAt(listOf(ListEngineResult.Index.of(1)))
                .variableDefinitions
                .single()
                .variable
        val second =
            resolver
                .instantiateObjectFragmentAt(listOf(ListEngineResult.Index.of(2)))
                .variableDefinitions
                .single()
                .variable

        assertNotEquals(first.instanceId, second.instanceId)
    }

    @Test
    fun `variable definition values are structural and require instances`() {
        val result = world.schema.requireObjectField("Query", "result")
        val consume = world.schema.requireObjectField("Query", "consume")
        val template = Arguments.Variable.of(result, "seed")
        val variable =
            template.instantiate(
                ResolverOccurrenceId.at(emptyList()),
            )
        val providerPath =
            listOf(ObjectEngineResult.Key.of(consume, mapOf("value" to 1)))
        val argumentDefinition =
            VariableDefinition.FromArgument.of(result.requireArg("seed"))

        assertEquals(
            InstantiatedObjectPathDefinition.of(variable, providerPath),
            InstantiatedObjectPathDefinition.of(variable, providerPath.toList()),
        )
        assertEquals(
            VariableInstanceDefinition.of(variable, argumentDefinition),
            VariableInstanceDefinition.of(variable, argumentDefinition),
        )
        assertNotNull(variable.instanceId)
        assertFailsWith<IllegalArgumentException> {
            InstantiatedObjectPathDefinition.of(template, providerPath)
        }
        assertFailsWith<IllegalArgumentException> {
            VariableInstanceDefinition.of(template, argumentDefinition)
        }
    }
}
