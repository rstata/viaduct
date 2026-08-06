package semantics.resolver03

import io.kotest.property.PropertyTesting
import kotlinx.coroutines.runBlocking
import model.fragmentFrom
import model.objectOf
import semantics.arbitrary.Config
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.NodeResolversEnabled
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.correctresolution.correctResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in high-volume Resolver03 coverage for deep dependency-heavy generated worlds. */
class ResolverStressTest {
    @Test
    fun `deep dependency-heavy arbitrary worlds resolve correctly`(): Unit =
        runBlocking {
            val requestedCases =
                configured(STRESS_CASES_PROPERTY, STRESS_CASES_ENV)
                    .toIntOrNull()
                    ?.also { cases ->
                        require(cases >= MINIMUM_STRESS_CASES && cases % CASES_PER_SCHEMA == 0) {
                            "$STRESS_CASES_PROPERTY/$STRESS_CASES_ENV must be at least " +
                                "$MINIMUM_STRESS_CASES and a multiple of $CASES_PER_SCHEMA"
                        }
                    }
                    ?: error("$STRESS_CASES_PROPERTY/$STRESS_CASES_ENV must be an integer")
            val seed =
                configured(STRESS_SEED_PROPERTY, STRESS_SEED_ENV)
                    .toLongOrNull()
                    ?: error("$STRESS_SEED_PROPERTY/$STRESS_SEED_ENV must be a Long")
            val counts =
                TestCaseCount(
                    schemas = requestedCases / CASES_PER_SCHEMA,
                    registriesPerSchema = REGISTRIES_PER_SCHEMA,
                    queriesPerSchema = QUERIES_PER_SCHEMA,
                )
            val config =
                Config.default +
                    (MinimumSelectionDepth to 4) +
                    (MaxSelectionDepth to 6) +
                    (SchemaObjectCount to 4..5) +
                    (ObjectFieldCount to 3..5) +
                    (QueryFieldCount to 2..4) +
                    (FieldArgumentWeight to 0.65) +
                    (ExplicitFieldResolverWeight to 0.7) +
                    (NullableTypeWeight to 0.15) +
                    (NullValueWeight to 0.05) +
                    (ErrorValueWeight to 0.02) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 0.85) +
                    (ResolverFragmentDepth to 3) +
                    (NodeResolversEnabled to false) +
                    (ResolverVariablesEnabled to false)
            var attemptedCases = 0
            var verifiedCases = 0
            var resolverApplications = 0
            val previousSeed = PropertyTesting.defaultSeed
            PropertyTesting.defaultSeed = seed

            try {
                checkResolverTestCases(counts, config) { testWorld, testCase ->
                    attemptedCases += 1
                    assertTrue(testCase.query.selectionDepth >= 4)
                    val world = testWorld.assumptions
                    val fragment = world.fragmentFrom(testCase.query.source)
                    testCase.registry.clearResolutionWitness()
                    val result =
                        context(world) {
                            world.objectOf("Query").resolve(fragment.subselections)
                        }
                    val witness = testCase.registry.resolutionWitness()
                    assertEquals(
                        context(world) {
                            result.registeredResolverApplicationIdentityCounts()
                        },
                        witness.applicationIdentityCounts(),
                    )
                    assertTrue(context(world) { result.correctResolution(fragment) })
                    resolverApplications += witness.applications.size
                    verifiedCases += 1
                }
            } finally {
                PropertyTesting.defaultSeed = previousSeed
                println(
                    "Resolver03 stress: seed=$seed, requestedCases=$requestedCases, " +
                        "attemptedCases=$attemptedCases, verifiedCases=$verifiedCases, " +
                        "resolverApplications=$resolverApplications, minimumDepth=4",
                )
            }

            assertEquals(requestedCases, attemptedCases)
            assertEquals(requestedCases, verifiedCases)
            assertTrue(resolverApplications >= requestedCases)
        }

    private fun configured(
        property: String,
        environment: String,
    ): String =
        System.getProperty(property)
            ?: System.getenv(environment)
            ?: error("Set $property or $environment; use the :semantics:resolver03Stress task")

    private companion object {
        const val REGISTRIES_PER_SCHEMA = 2
        const val QUERIES_PER_SCHEMA = 5
        const val CASES_PER_SCHEMA = REGISTRIES_PER_SCHEMA * QUERIES_PER_SCHEMA
        const val MINIMUM_STRESS_CASES = 10_000
        const val STRESS_CASES_ENV = "RESOLVER03_STRESS_CASES"
        const val STRESS_CASES_PROPERTY = "resolver03.stress.cases"
        const val STRESS_SEED_ENV = "RESOLVER03_STRESS_SEED"
        const val STRESS_SEED_PROPERTY = "resolver03.stress.seed"
    }
}
