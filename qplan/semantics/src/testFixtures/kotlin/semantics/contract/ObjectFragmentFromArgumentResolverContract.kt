package semantics.contract

import model.Value
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
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
        val consumeArguments = mutableListOf<Value.Arguments>()
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
                applicationObserver = { field, _, arguments, _ ->
                    if (field.fieldName == "consume") {
                        consumeArguments += arguments
                    }
                },
            )
        val world = testWorld.assumptions
        val resultField = world.schema.objectField("Query", "result")
        val variable = Value.Variable.of(resultField, "seed")
        val firstKey =
            Value.GroundKey.of(
                resultField,
                mapOf("seed" to 7),
            )
        val secondKey = Value.GroundKey.of(resultField, mapOf("seed" to 8))
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  first: result(seed: 7)
                  second: result(seed: 8)
                }
                """.trimIndent(),
            )

        val resolved = resolveAndValidate(world, world.objectOf("Query"), fragment)

        assertEquals(Value.Int.of(14), resolved.getCell(firstKey).get())
        assertEquals(Value.Int.of(16), resolved.getCell(secondKey).get())
        assertEquals(
            listOf(
                Value.Arguments.of(
                    world.schema.field("Query", "consume"),
                    mapOf("value" to 7),
                ),
                Value.Arguments.of(
                    world.schema.field("Query", "consume"),
                    mapOf("value" to 8),
                ),
            ),
            consumeArguments,
        )
        val resolver = world.resolverRegistry.resolver(resultField)
        listOf(
            firstKey to Value.Int.of(7),
            secondKey to Value.Int.of(8),
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
                assertEquals(expectedValue, world.getBinding(boundVariable))
            }
        }
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
            Value.GroundKey.of(
                world.schema.objectField("Query", "one"),
                mapOf("seed" to 7),
            )
        val fragment = world.fragmentFrom("fragment ignored on Query { one(seed: 7) }")

        val resolved = resolveAndValidate(world, world.objectOf("Query"), fragment)

        assertEquals(Value.Int.of(8), resolved.getCell(oneKey).get())
    }
}
