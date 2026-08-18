package semantics.correctresolution

import model.ObjectEngineResult

import model.Value
import model.emptyFragmentOf
import model.engineResultOf
import model.fragmentFrom
import model.testing.TestWorld
import model.testing.fromArgument
import kotlin.test.Test
import kotlin.test.assertTrue

class IsClosedUnderResolverDemandTest {
    @Test
    fun `non-node object does not require an id field`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val result =
            world.engineResultOf("Profile") {
                "name" resolvesTo "Ada"
            }

        assertTrue(context(world) { result.isClosedUnderResolverDemand() })
    }

    @Test
    fun `bound variables are substituted in nested resolver demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Child {
                      consume(value: Int!): Int!
                    }

                    type Parent {
                      child: Child!
                      result(seed: Int!): Int!
                    }

                    type Query {
                      parent: Parent!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.objectField("Parent", "result")
                    val consume = schema.objectField("Child", "consume")
                    val parent = schema.objectField("Query", "parent")
                    mapOf(
                        result to
                            model.testing.fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Parent {
                                      child {
                                        consume(value: ${'$'}seed)
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ -> Value.Int.of(14) },
                        consume to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Child"),
                            ) { _, _ -> Value.Int.of(14) },
                        parent to
                            model.testing.fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ -> null },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Parent", "result")
                    mapOf(
                        Value.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultField = world.schema.objectField("Parent", "result")
        val resultKey = ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 7))
        val variable = Value.Variable.of(resultField, "seed")
        val stamped = variable.stamp(listOf(resultKey))
        world.declareBinding(stamped)
        world.completeBinding(stamped, 7)
        val result =
            world.engineResultOf("Parent") {
                "child" resolvesTo
                    engineResultOf("Child") {
                        field("consume", "value" to 7) resolvesTo 14
                    }
                field("result", "seed" to 7) resolvesTo 14
            }

        assertTrue(context(world) { result.isClosedUnderResolverDemand() })
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type Profile {
              name: String!
            }

            type Query {
              profile: Profile!
            }
            """.trimIndent()
    }
}
