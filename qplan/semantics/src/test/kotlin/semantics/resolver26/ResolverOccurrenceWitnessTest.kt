package semantics.resolver26

import model.requireObjectField
import semantics.contract.selectionValues
import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Schema
import model.TypeExpr
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolutionOccurrenceApplicationLog
import semantics.arbitrary.ResolutionOccurrenceWitness
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ResolverOccurrenceWitnessTest {
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
                    val payloadType =
                        (items.type as TypeExpr.List<Schema.OutputTypeDef>).elementType
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
