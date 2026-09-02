package semantics.correctresolution

import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.engineResultOf
import model.fragmentFrom
import model.merge
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.ObjectSelectionForest
import model.testing.fieldResolverOf
import viaduct.graphql.schema.ViaductSchema
import model.testing.TestWorld
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CorrectResolutionTest {
    @Test
    fun `selections must be rooted at Query`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL).assumptions
        val result = world.engineResultOf("Query")
        val profileSelections =
            ObjectSelectionForest.of(
                type = world.schema.requireType("Profile") as ViaductSchema.Object,
                selections = emptyList(),
            )

        assertFailsWith<IllegalArgumentException> {
            context(world) {
                result.correctResolution(profileSelections)
            }
        }
    }

    @Test
    fun `resolver query fragment witness participates in correctness`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      source: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.requireObjectField("Query", "source")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        """
                                        fragment ignored on Query {
                                          aliased: source
                                        }
                                        """.trimIndent(),
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.get("aliased")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val consumer = world.schema.requireObjectField("Query", "consumer")
        val consumerKey = ObjectEngineResult.GroundKey.of(consumer, emptyMap())
        val selections =
            world
                .fragmentFrom(
                    """
                    fragment ignored on Query {
                      consumer
                    }
                    """.trimIndent(),
                ).subselections
                .merge(world.schema.requireQueryTypeDef())
        val result =
            world.engineResultOf("Query") {
                "consumer" resolvesTo 7
            }

        assertFalse(context(world) { result.correctResolution(selections) })

        world.queryValues[ResolverOccurrenceId.at(result, listOf(consumerKey))] =
            world.engineResultOf("Query") {
                "source" resolvesTo 8
            }
        assertFalse(context(world) { result.correctResolution(selections) })

        world.queryValues[ResolverOccurrenceId.at(result, listOf(consumerKey))] =
            world.engineResultOf("Query") {
                "source" resolvesTo 7
            }
        assertTrue(context(world) { result.correctResolution(selections) })
    }

    @Test
    fun `resolver fromArgument binding must agree with its owning arguments`() {
        val world =
            TestWorld.fromDSL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    extend type Query {
                      consumer(seed: Int!): Int!
                        @resolver(
                          of: "source(value: ${'$'}seed)"
                          result: "sum(source)"
                        )
                      source(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            ).assumptions
        val consumer = world.schema.requireObjectField("Query", "consumer")
        val source = world.schema.requireObjectField("Query", "source")
        val consumerKey =
            ObjectEngineResult.GroundKey.of(
                consumer,
                mapOf("seed" to 7),
            )
        val result =
            ObjectEngineResult.of(
                type = world.schema.requireQueryTypeDef(),
                mutable = true,
            )
        val occurrenceId = ResolverOccurrenceId.at(result, listOf(consumerKey))
        val variable = Arguments.Variable.of(consumer, "seed").instantiate(occurrenceId)
        world.bindVariable(requireNotNull(variable.instanceId), 99)
        val symbolicSourceKey =
            ObjectEngineResult.ObjectKey.of(
                field = source,
                arguments = Arguments.of(source, mapOf("value" to variable)),
            )
        result.reserveCell(symbolicSourceKey).apply {
            setValue(99)
            setAccessResult(true)
        }
        result.reserveCell(consumerKey).apply {
            setValue(99)
            setAccessResult(true)
        }
        result.freeze()
        val selections =
            world
                .fragmentFrom(
                    """
                    fragment ignored on Query {
                      consumer(seed: 7)
                    }
                    """.trimIndent(),
                ).subselections
                .merge(world.schema.requireQueryTypeDef())

        assertFalse(context(world) { result.correctResolution(selections) })
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
