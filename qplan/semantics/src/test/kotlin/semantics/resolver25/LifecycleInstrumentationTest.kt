package semantics.resolver25

import model.Arguments

import semantics.contract.selectionValues

import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import org.junit.jupiter.api.Test
import semantics.contract.Resolver25LifecycleTraceValidators
import semantics.contract.assertValidResolver25LifecycleTrace
import semantics.contract.validate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifecycleInstrumentationTest {
    @Test
    fun `records a contiguous externally valid lifecycle trace`() {
        val trace = fromArgumentTrace()

        trace.assertValidResolver25LifecycleTrace()
        assertEquals(trace.indices.map(Int::toLong), trace.map { event -> event.sequence })
        assertTrue(
            trace.filterIsInstance<Resolver25LifecycleEvent.BindingDeclared>()
                .any { event ->
                    event.source is Resolver25BindingSource.FromArgument
                },
        )
    }

    @Test
    fun `sequence validator rejects a missing event`() {
        val trace = fromArgumentTrace().drop(1)

        assertViolation("sequence.non-contiguous", trace)
    }

    @Test
    fun `reference validator rejects resolver finish before start`() {
        val trace = fromArgumentTrace().toMutableList()
        val startIndex =
            trace.indexOfFirst { event ->
                event is Resolver25LifecycleEvent.ResolverStarted
            }
        val finishIndex =
            trace.indexOfFirst { event ->
                event is Resolver25LifecycleEvent.ResolverFinished
            }
        val finish = trace.removeAt(finishIndex)
        trace.add(startIndex, finish)

        assertViolation("resolver.finished-before-start", trace)
    }

    @Test
    fun `one-shot validator rejects duplicate resolver start`() {
        val trace = fromArgumentTrace().toMutableList()
        val start =
            trace.filterIsInstance<Resolver25LifecycleEvent.ResolverStarted>()
                .first()
        trace += start

        assertViolation("resolver.duplicate-start", trace)
    }

    @Test
    fun `completion validator rejects omitted resolver application events`() {
        val trace =
            fromArgumentTrace()
                .filterNot { event ->
                    event is Resolver25LifecycleEvent.ResolverStarted ||
                        event is Resolver25LifecycleEvent.ResolverFinished
                }

        assertViolation("resolver.not-started", trace)
    }

    @Test
    fun `input validator rejects resolver start before contribution installation`() {
        val trace = fromArgumentTrace().toMutableList()
        val submission =
            trace.filterIsInstance<Resolver25LifecycleEvent.DemandSubmitted>()
                .first { event -> event.consumerCoordinate != null }
        val startIndex =
            trace.indexOfFirst { event ->
                event is Resolver25LifecycleEvent.ResolverStarted &&
                    event.coordinate == submission.consumerCoordinate
            }
        val start = trace.removeAt(startIndex)
        val installationIndex =
            trace.indexOfFirst { event ->
                event is Resolver25LifecycleEvent.ContributionInstalled &&
                    event.contributionId == submission.contributionId
            }
        trace.add(installationIndex, start)

        assertViolation("resolver.started-before-input-installation", trace)
    }

    @Test
    fun `merge validator rejects a phase inconsistent with sealing`() {
        val trace = fromArgumentTrace().toMutableList()
        val mergeIndex =
            trace.indexOfFirst { event ->
                event is Resolver25LifecycleEvent.GroundedDemandMerged
            }
        val merge =
            trace[mergeIndex] as Resolver25LifecycleEvent.GroundedDemandMerged
        trace[mergeIndex] = merge.copy(beforeLaunch = !merge.beforeLaunch)

        assertViolation(
            if (merge.beforeLaunch) {
                "contribution.postlaunch-merge-before-seal"
            } else {
                "contribution.prelaunch-merge-after-seal"
            },
            trace,
        )
    }

    @Test
    fun `completion validator rejects a missing binding completion`() {
        val trace =
            fromArgumentTrace()
                .filterNot { event ->
                    event is Resolver25LifecycleEvent.BindingCompleted
                }

        assertViolation("binding.not-completed", trace)
    }

    @Test
    fun `reference validator rejects grounding before binding completion`() {
        val trace =
            fromArgumentTrace()
                .filterNot { event ->
                    event is Resolver25LifecycleEvent.BindingCompleted
                }

        assertViolation("binding.grounded-before-completion", trace)
    }

    @Test
    fun `publication validator rejects value publication before output`() {
        val trace = fromArgumentTrace().toMutableList()
        val publicationIndex =
            trace.indexOfFirst { event ->
                event is Resolver25LifecycleEvent.ValuePublished
            }
        val publication = trace.removeAt(publicationIndex)
        val outputIndex =
            trace.indexOfFirst { event ->
                event is Resolver25LifecycleEvent.OutputAvailable
            }
        trace.add(outputIndex, publication)

        assertViolation("value.published-before-output", trace)
    }

    private fun assertViolation(
        code: String,
        trace: List<Resolver25LifecycleEvent>,
    ) {
        val violations =
            Resolver25LifecycleTraceValidators.successfulTrace.validate(trace)
        assertTrue(
            violations.any { violation -> violation.code == code },
            "Expected $code; observed ${violations.map { violation -> violation.code }}",
        )
    }

    private fun fromArgumentTrace(): List<Resolver25LifecycleEvent> {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result(seed: Int!): Int!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val consume = schema.objectField("Query", "consume")
                    mapOf(
                        schema.objectField("Query", "result") to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ignored on Query { consume(value: ${'$'}seed) }",
                                ),
                            ) { input, _ ->
                                input.selectionValues().getValue(consume.fieldName)
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                val value =
                                    arguments.fieldValues.getValue("value") as Int
                                value * 2
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.objectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "seed") to
                            schema.fromArgument(result, "seed"),
                    )
                },
            )
        val world = testWorld.assumptions
        val fragment =
            world.fragmentFrom(
                """
                fragment ignored on Query {
                  result(seed: 7)
                  result(seed: 7)
                }
                """.trimIndent(),
            )
        val trace = mutableListOf<Resolver25LifecycleEvent>()

        context(world) {
            resolveObserved(
                fragment.subselections,
                trace::add,
            )
        }
        return trace
    }
}
