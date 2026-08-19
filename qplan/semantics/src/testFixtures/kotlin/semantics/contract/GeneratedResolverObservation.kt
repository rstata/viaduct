package semantics.contract

import model.Assumptions
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Fragment
import model.SelectionForest
import model.applicableGroundSelections
import model.fragmentFrom
import model.groundKey
import model.objectOf
import model.testing.TestWorld
import semantics.arbitrary.ResolverApplicationRecord
import semantics.arbitrary.ResolverTestCase
import semantics.correctresolution.correctResolution
import semantics.correctresolution.conformsToResolvers
import semantics.correctresolution.conformsToSelections
import semantics.correctresolution.isClosedUnderResolverDemand
import semantics.correctresolution.rootedAndWellTyped
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One generated resolver execution and the request-local state needed to validate it. */
data class GeneratedResolutionObservation(
    val world: Assumptions,
    val fragment: Fragment,
    val subject: ResolverResolutionObservation,
) {
    val result: ObjectEngineResult
        get() = subject.result
}

/** Both executions of one generated case and the ordinary execution's application witness. */
data class GeneratedCaseObservation(
    val testCase: ResolverTestCase,
    val ordinary: GeneratedResolutionObservation,
    val permutationEquivalent: GeneratedResolutionObservation,
    val ordinaryApplications: List<ResolverApplicationRecord>,
) {
    val executions: List<GeneratedResolutionObservation>
        get() = listOf(ordinary, permutationEquivalent)
}

/** One independently selectable judgment over a completed generated case. */
fun interface GeneratedCaseAssertion {
    fun assertThat(observation: GeneratedCaseObservation)
}

/** Reusable generated-case judgments, composed by each contract according to its claims. */
object GeneratedCaseAssertions {
    val correctResolution =
        GeneratedCaseAssertion { observation ->
            observation.executions.forEach { execution ->
                context(execution.world) {
                    val correct = execution.result.correctResolution(execution.fragment)
                    if (!correct) {
                        fun diagnostic(
                            name: String,
                            value: () -> Any?,
                        ): String =
                            "$name=" +
                                runCatching(value).fold(
                                    onSuccess = Any?::toString,
                                    onFailure = { failure ->
                                        "${failure::class.simpleName}: ${failure.message}"
                                    },
                                )

                        listOf(
                            diagnostic("rootedAndWellTyped") {
                                execution.result.rootedAndWellTyped()
                            },
                            diagnostic("conformsToSelections") {
                                execution.result.conformsToSelections(
                                    execution.fragment.subselections,
                                )
                            },
                            diagnostic("isClosedUnderResolverDemand") {
                                execution.result.isClosedUnderResolverDemand()
                            },
                            diagnostic("unclosedResolverOccurrences") {
                                execution.result.unclosedRegisteredResolverOccurrences()
                            },
                            diagnostic("conformsToResolvers") {
                                execution.result.conformsToResolvers()
                            },
                        ).joinToString(separator = "\n")
                            .let { diagnostics ->
                                assertTrue(actual = false, message = diagnostics)
                            }
                    }
                }
            }
        }

    val permutationEquivalentResult =
        GeneratedCaseAssertion { observation ->
            assertTrue(
                context(observation.ordinary.world) {
                    observation.ordinary.result.sameSelectedResultAs(
                        other = observation.permutationEquivalent.result,
                        selections = observation.ordinary.fragment.subselections,
                    )
                },
            )
        }

    val exactOrdinaryApplicationCounts =
        GeneratedCaseAssertion { observation ->
            val expected =
                context(observation.ordinary.world) {
                    observation.ordinary.result.registeredResolverApplicationIdentityCounts()
                }
            assertEquals(
                expected,
                observation.ordinaryApplications
                    .groupingBy(ResolverApplicationRecord::identity)
                    .eachCount(),
            )
        }

    val objectPathBindings =
        GeneratedCaseAssertion { observation ->
            observation.executions.forEach { execution ->
                context(execution.world) {
                    execution.result.validateObjectPathBindings()
                }
            }
        }

    val defaultGeneratedContract =
        listOf(
            correctResolution,
            permutationEquivalentResult,
        )
}

/** Assertion policy independently extended by each generated resolver test subject. */
interface GeneratedCaseAssertionPolicy : ResolverContract {
    val generatedCaseAssertions: List<GeneratedCaseAssertion>
        get() = GeneratedCaseAssertions.defaultGeneratedContract
}

fun GeneratedCaseObservation.assertAll(
    assertions: Iterable<GeneratedCaseAssertion>,
): GeneratedCaseObservation =
    apply {
        assertions.forEach { assertion -> assertion.assertThat(this) }
    }

/** Executes an ordinary and permutation-equivalent generated query with fresh assumptions. */
fun ResolverContract.observeGeneratedCase(
    testWorld: TestWorld,
    testCase: ResolverTestCase,
): GeneratedCaseObservation {
    testCase.registry.clearResolutionWitness()
    val ordinary =
        observeGeneratedResolution(
            testWorld = testWorld,
            querySource = testCase.query.source,
        )
    val ordinaryApplications = testCase.registry.resolutionWitness().applications
    val permutationEquivalent =
        testCase.registry.withoutResolutionWitnessCapture {
            observeGeneratedResolution(
                testWorld = testWorld,
                querySource = testCase.query.permutationEquivalentSource,
            )
        }
    return GeneratedCaseObservation(
        testCase = testCase,
        ordinary = ordinary,
        permutationEquivalent = permutationEquivalent,
        ordinaryApplications = ordinaryApplications,
    )
}

private fun ResolverContract.observeGeneratedResolution(
    testWorld: TestWorld,
    querySource: String,
): GeneratedResolutionObservation {
    val world = testWorld.newAssumptions(selectiveResolvers)
    val fragment = world.fragmentFrom(querySource)
    val subject =
        observeResolution(
            world,
            world.objectOf("Query"),
            fragment.subselections,
        )
    return GeneratedResolutionObservation(
        world = world,
        fragment = fragment,
        subject = subject,
    )
}

context(world: Assumptions)
private fun EngineResult?.sameSelectedResultAs(
    other: EngineResult?,
    selections: SelectionForest,
): Boolean {
    if (this == null || other == null) return this == other
    return when (this) {
        ErrorEngineResult -> other == ErrorEngineResult
        is ListEngineResult ->
            other is ListEngineResult &&
                typeExpr == other.typeExpr &&
                size == other.size &&
                indices.all { index ->
                    this[index]
                        .get()
                        .sameSelectedResultAs(other[index].get(), selections)
                }
        is ObjectEngineResult ->
            other is ObjectEngineResult &&
                type == other.type &&
                selections
                    .applicableGroundSelections(type)
                    .byGroundKey()
                    .values
                    .all { selection ->
                        val key = selection.groundKey()
                        key in keys &&
                            key in other.keys &&
                            getCell(key)
                                .get()
                                .sameSelectedResultAs(
                                    other = other.getCell(key).get(),
                                    selections = selection.subselections,
                                )
                    }
        else ->
            other != ErrorEngineResult &&
                other !is ListEngineResult &&
                other !is ObjectEngineResult &&
                this == other
    }
}
