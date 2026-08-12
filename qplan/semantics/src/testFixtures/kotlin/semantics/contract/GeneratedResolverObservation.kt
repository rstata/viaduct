package semantics.contract

import model.Assumptions
import model.EngineResult
import model.Fragment
import model.fragmentFrom
import model.objectOf
import model.sameCompletedResultAs
import model.testing.TestWorld
import semantics.arbitrary.ResolverApplicationRecord
import semantics.arbitrary.ResolverTestCase
import semantics.correctresolution.correctResolution
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One generated resolver execution and the request-local state needed to validate it. */
data class GeneratedResolutionObservation(
    val world: Assumptions,
    val fragment: Fragment,
    val result: EngineResult.Object,
)

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
                assertTrue(
                    context(execution.world) {
                        execution.result.correctResolution(execution.fragment)
                    },
                )
            }
        }

    val permutationEquivalentResult =
        GeneratedCaseAssertion { observation ->
            assertTrue(
                observation.ordinary.result.sameCompletedResultAs(
                    observation.permutationEquivalent.result,
                ),
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
    val result =
        resolve(
            world,
            world.objectOf("Query"),
            fragment.subselections,
        )
    return GeneratedResolutionObservation(
        world = world,
        fragment = fragment,
        result = result,
    )
}
