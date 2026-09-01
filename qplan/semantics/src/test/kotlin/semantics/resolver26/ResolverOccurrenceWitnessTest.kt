package semantics.resolver26

import model.requireObjectField
import semantics.contract.selectionValues
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import viaduct.graphql.schema.ViaductSchema
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolutionOccurrenceApplicationLog
import semantics.arbitrary.ResolutionOccurrenceWitness
import semantics.contract.registeredResolverApplicationIdentityCounts
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ResolverOccurrenceWitnessTest {
    @Test
    fun `occurrence oracle includes query roots and rejects wrong-root applications`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      source: Int!
                      first: Int!
                      second: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.requireObjectField("Query", "source")
                    val queryFragment =
                        schema.fragmentFrom(
                            "fragment SourceQuery on Query { source }",
                        )
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        schema.requireObjectField("Query", "first") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = queryFragment,
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("source")
                            },
                        schema.requireObjectField("Query", "second") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = queryFragment,
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("source")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                "fragment QueryResult on Query { first second }",
            )
        val log = ResolutionOccurrenceApplicationLog()

        val result =
            context(world) {
                resolve(
                    selections = fragment.subselections,
                    coroutineContext = EmptyCoroutineContext,
                    applicationObserver = { application ->
                        log.record(
                            resolverOccurrenceId = application.resolverOccurrenceId,
                            occurrencePath = application.occurrencePath,
                            field =
                                FieldCoordinate(
                                    application.field.containingDef.name,
                                    application.field.name,
                                ),
                            arguments = application.arguments,
                            input = application.input,
                            suppliedDemand = application.suppliedDemand,
                        )
                    },
                )
            }
        val witness = log.snapshot()
        val expected =
            context(world) {
                result.registeredResolverOccurrenceApplicationIdentityCounts()
            }

        assertEquals(expected, witness.applicationIdentityCounts())
        assertEquals(4, expected.values.sum())
        assertEquals(
            context(world) {
                result.registeredResolverApplicationIdentityCounts()
            },
            witness.applications
                .groupingBy { application -> application.application.identity }
                .eachCount(),
        )
        assertEquals(2, world.queryValues.size)

        val sourceApplications =
            witness.applications.filter { application ->
                application.application.key.field == FieldCoordinate("Query", "source")
            }
        assertEquals(2, sourceApplications.size)
        val first = sourceApplications.first()
        val second = sourceApplications.last()
        assertEquals(first.occurrencePath, second.occurrencePath)
        assertEquals(first.application.identity, second.application.identity)
        assertNotEquals(first.resolverOccurrenceId, second.resolverOccurrenceId)

        val wrongRoot =
            ResolutionOccurrenceWitness(
                witness.applications.map { application ->
                    if (application == second) {
                        second.copy(resolverOccurrenceId = first.resolverOccurrenceId)
                    } else {
                        application
                    }
                },
            )
        assertNotEquals(
            expected,
            wrongRoot.applicationIdentityCounts(),
            "Duplicating one Query root and omitting another must fail the occurrence oracle",
        )
    }

    @Test
    fun `occurrence oracle rejects duplicate-one omit-one for equal-input list elements`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      items: [Payload!]!
                    }

                    type Payload {
                      computed: Int!
                      base: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val items = schema.requireObjectField("Query", "items")
                    val payloadType = checkNotNull(items.type.unwrapList())
                    val baseKey =
                        ObjectEngineResult.GroundKey.of(
                            schema.requireObjectField("Payload", "base"),
                            emptyMap(),
                        )
                    mapOf(
                        items to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                listOf(
                                    schema.objectOf("Payload") {
                                        "base" setTo 10
                                    },
                                    schema.objectOf("Payload") {
                                        "base" setTo 10
                                    },
                                )
                            },
                        schema.requireObjectField("Payload", "computed") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment PayloadInput on Payload { base }",
                                ),
                            ) { input, _ ->
                                input.selectionValues().getValue(baseKey.field.name)
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                "fragment QueryResult on Query { items { computed } }",
            )
        val log = ResolutionOccurrenceApplicationLog()

        val result: ObjectEngineResult =
            context(world) {
                resolve(
                    selections = fragment.subselections,
                    coroutineContext = EmptyCoroutineContext,
                    applicationObserver = { application ->
                        log.record(
                            resolverOccurrenceId = application.resolverOccurrenceId,
                            occurrencePath = application.occurrencePath,
                            field =
                                FieldCoordinate(
                                    application.field.containingDef.name,
                                    application.field.name,
                                ),
                            arguments = application.arguments,
                            input = application.input,
                            suppliedDemand = application.suppliedDemand,
                        )
                    },
                )
            }
        val witness = log.snapshot()
        val expected =
            context(world) {
                result.registeredResolverOccurrenceApplicationIdentityCounts()
            }

        assertEquals(expected, witness.applicationIdentityCounts())

        val computedApplications =
            witness.applications.filter { application ->
                application.application.key.field ==
                    FieldCoordinate("Payload", "computed")
            }
        assertEquals(2, computedApplications.size)
        assertEquals(
            1,
            computedApplications
                .map { application -> application.application.identity }
                .toSet()
                .size,
            "The two list positions must have equal field, arguments, and materialized input",
        )
        val first =
            computedApplications.single { application ->
                ListEngineResult.Index.of(0) in application.occurrencePath
            }
        val second =
            computedApplications.single { application ->
                ListEngineResult.Index.of(1) in application.occurrencePath
            }
        val malformed =
            ResolutionOccurrenceWitness(
                witness.applications.map { application ->
                    if (application == second) first else application
                },
            )

        assertNotEquals(
            expected,
            malformed.applicationIdentityCounts(),
            "Duplicating list position 0 and omitting position 1 must fail the occurrence oracle",
        )
    }
}
