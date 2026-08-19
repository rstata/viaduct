package semantics.contract

import model.Arguments

import model.ObjectEngineResult

import model.VariableBinding
import model.emptyFragmentOf
import model.fragmentFrom
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Contract for nonempty object fragments with variables bound from resolver arguments.
 */
interface ObjectFragmentFromArgumentResolverContract :
    ResolverContract,
    NestedFromArgumentDemandResolverContract,
    PassiveFromArgumentDemandResolverContract,
    RecursiveListFromArgumentDemandResolverContract {
    @Test
    fun `resolves input selected with a fromArgument variable`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!): Int!
                        @resolver(
                          of: "consume(value: ${'$'}seed)"
                          result: "sum(consume)"
                        )
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value, ${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.objectField("Query", "result")
        val variable = Arguments.Variable.of(resultField, "seed")
        val firstKey =
            ObjectEngineResult.GroundKey.of(
                resultField,
                mapOf("seed" to 7),
            )
        val secondKey = ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 8))
        val resolved =
            resolveAndValidate(
                world,
                """
                query Resolve(${'$'}first: Int!, ${'$'}second: Int!) {
                  first: result(seed: ${'$'}first)
                  second: result(seed: ${'$'}second)
                }
                """.trimIndent(),
                variables = mapOf("first" to 7, "second" to 8),
            )

        assertEquals(14, resolved.getCell(firstKey).get())
        assertEquals(16, resolved.getCell(secondKey).get())
        testWorld.applicationArguments.assertArguments(
            world.schema.field("Query", "consume"),
            mapOf("value" to 7),
            mapOf("value" to 8),
        )
        val resolver = world.resolverRegistry.resolver(resultField)
        listOf(
            firstKey to 7,
            secondKey to 8,
        ).forEach { (groundKey, expectedValue) ->
            val path = listOf(groundKey)
            val selectionStampedVariables =
                resolver
                    .selectionStampedVariableDefinitions(path)
                    .map { definition -> definition.variable }
            val boundVariables =
                selectionStampedVariables
                    .takeIf { variables ->
                        variables.isNotEmpty() && variables.all(world::isBound)
                    } ?: listOf(variable.stamp(path))
            boundVariables.forEach { boundVariable ->
                assertEquals(VariableBinding.of(expectedValue), world.getBinding(boundVariable))
            }
        }
    }

    @Test
    fun `resolves a fromArgument variable whose name differs from its argument`() {
        val resultFragment =
            """
            fragment Result on Query {
              consume(value: ${'$'}argumentValue)
            }
            """.trimIndent()
        // The compact DSL reserves $argname for same-named argument variables.
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      result(value: Int!): Int!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.objectField("Query", "result")
                    val consume = schema.objectField("Query", "consume")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.selectionValues().getValue("consume")
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "argumentValue") to
                            schema.fromArgument(result, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "result"),
                mapOf("value" to 7),
            )
        val resolved =
            resolveAndValidate(world, "query { result(value: 7) }")

        assertEquals(7, resolved.getCell(resultKey).get())
    }

    @Test
    fun `resolves a transitive chain of fromArgument variables`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      one(seed: Int!): Int!
                        @resolver(of: "two(value: ${'$'}seed)", result: "sum(two)")
                      two(value: Int!): Int!
                        @resolver(of: "three(value: ${'$'}value)", result: "sum(three)")
                      three(value: Int!): Int!
                        @resolver(result: "sumplus1(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val oneKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.objectField("Query", "one"),
                mapOf("seed" to 7),
            )
        val resolved = resolveAndValidate(world, "query { one(seed: 7) }")

        assertEquals(8, resolved.getCell(oneKey).get())
    }
}
