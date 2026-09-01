package semantics.resolver26

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.EngineResult
import model.ObjectEngineResult
import model.Fragment
import model.fragmentFrom
import model.objectOf
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.RegisteredResolverOccurrence
import semantics.arbitrary.ResolutionWitness
import semantics.arbitrary.ResolverTestRun
import semantics.arbitrary.ResolverTestExecution
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.configuredResolverTestExecution
import semantics.arbitrary.executeResolverTestCases
import semantics.arbitrary.registeredResolverOccurrences
import semantics.contract.registeredResolverApplicationIdentityCounts
import semantics.contract.validateObjectPathBindings
import semantics.correctresolution.correctResolution
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unfiltered Resolver26 stress: every generated registry/query product is resolved and validated.
 */
class ResolverBroadStressTest {
    @Test
    fun `broad full-feature worlds resolve correctly`(): Unit =
        runBlocking {
            val broadProfile: Resolver26BroadStressProfile = configuredProfile()
            runResolver26BroadStress(
                requiredSignatures = broadProfile.requiredSignatures,
                propertyProfile = broadProfile.propertyProfile,
                counts = configuredCounts(broadProfile),
                config = broadProfile.config,
                seed = configuredSeed(),
            )
        }

    // Returns the named generator distribution selected for this run.
    private fun configuredProfile(): Resolver26BroadStressProfile {
        val configured: String =
            System.getProperty(PROFILE_PROPERTY)
                ?: System.getenv(PROFILE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.profile")
                ?: Resolver26BroadStressProfile.BALANCED.propertyProfile
        return Resolver26BroadStressProfile.fromConfigured(configured)
    }

    // Returns the configured S:R:Q product dimensions.
    private fun configuredCounts(
        broadProfile: Resolver26BroadStressProfile,
    ): TestCaseCount {
        val configured: String =
            System.getProperty(SIZE_PROPERTY)
                ?: System.getenv(SIZE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.size")
                ?: broadProfile.defaultSize
        val dimensions: List<String> = configured.split(':')
        require(dimensions.size == 3) {
            "$SIZE_PROPERTY/$SIZE_ENVIRONMENT must have S:R:Q form: $configured"
        }
        val parsed: List<Int> =
            dimensions.map { dimension ->
                dimension.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: error(
                        "$SIZE_PROPERTY/$SIZE_ENVIRONMENT must have positive dimensions: " +
                            configured,
                    )
            }
        return TestCaseCount(
            schemas = parsed[0],
            registriesPerSchema = parsed[1],
            queriesPerSchema = parsed[2],
        )
    }

    // Returns the explicit seed required for reproducible broad generation.
    private fun configuredSeed(): Long {
        val configured: String =
            System.getProperty(SEED_PROPERTY)
                ?: System.getenv(SEED_ENVIRONMENT)
                ?: System.getProperty("resolver.property.seed")
                ?: error(
                    "Set $SEED_PROPERTY or $SEED_ENVIRONMENT; use the " +
                        ":semantics:resolver26BroadStress task",
                )
        return configured.toLongOrNull()
            ?: error("$SEED_PROPERTY/$SEED_ENVIRONMENT must be a Long: $configured")
    }

    private companion object {
        const val PROFILE_PROPERTY = "resolver26.broad.stress.profile"
        const val PROFILE_ENVIRONMENT = "RESOLVER26_BROAD_STRESS_PROFILE"
        const val SIZE_PROPERTY = "resolver26.broad.stress.size"
        const val SIZE_ENVIRONMENT = "RESOLVER26_BROAD_STRESS_SIZE"
        const val SEED_PROPERTY = "resolver26.broad.stress.seed"
        const val SEED_ENVIRONMENT = "RESOLVER26_BROAD_STRESS_SEED"
    }
}

// Resolves and independently validates every case in one Resolver26 generated product.
internal suspend fun runResolver26BroadStress(
    requiredSignatures: Set<Resolver26StructuralSignature>,
    propertyProfile: String,
    counts: TestCaseCount,
    config: Config,
    seed: Long,
    execution: ResolverTestExecution = configuredResolverTestExecution(counts, propertyProfile),
): Int {
    val startedAt: Long = System.nanoTime()
    var attemptedCases = 0
    var resolutionCalls = 0
    var completedCases = 0
    var resolverApplications = 0
    var generatedArgumentVariables = 0
    var generatedObjectPathVariables = 0
    var activatedArgumentVariableApplications = 0
    var activatedObjectPathVariableApplications = 0
    var maximumProviderPathLength = 0
    var maximumVariableUseDepth = 0
    val observedSignatures: MutableSet<Resolver26StructuralSignature> = linkedSetOf()

    try {
        val run: ResolverTestRun =
            executeResolverTestCases(
                execution = execution,
                config = config,
                profile = propertyProfile,
                seed = seed,
            ) { testWorld, testCase ->
                attemptedCases += 1
                generatedArgumentVariables +=
                    testCase.registry.features.fromArgumentVariableCount
                generatedObjectPathVariables +=
                    testCase.registry.features.fromObjectFieldVariableCount
                maximumProviderPathLength =
                    maxOf(
                        maximumProviderPathLength,
                        testCase.registry.features.maximumFromObjectFieldPathLength,
                    )
                maximumVariableUseDepth =
                    maxOf(
                        maximumVariableUseDepth,
                        testCase.registry.features.maximumFromObjectFieldVariableUseDepth,
                    )

                val world: Assumptions =
                    testWorld.newAssumptions(selectiveResolvers = true)
                val fragment: Fragment = world.fragmentFrom(testCase.query.source)
                testCase.registry.clearResolutionWitness()
                resolutionCalls += 1
                val result: ObjectEngineResult =
                    context(world) {
                        resolve(fragment.subselections)
                    }
                val witness: ResolutionWitness = testCase.registry.resolutionWitness()
                val occurrences: List<RegisteredResolverOccurrence> =
                    context(world) {
                        result.registeredResolverOccurrences(world.resolverRegistry)
                    }
                observedSignatures +=
                    resolver26StructuralSignatures(
                        occurrences = occurrences,
                        witness = witness,
                        registry = testCase.registry,
                    )
                resolverApplications += witness.applications.size
                witness.applications.forEach { application ->
                    if (
                        testCase.registry.sourceResolverHasFromArgumentVariables(
                            application.key.field,
                        )
                    ) {
                        activatedArgumentVariableApplications += 1
                    }
                    if (
                        testCase.registry.sourceResolverHasFromObjectFieldVariables(
                            application.key.field,
                        )
                    ) {
                        activatedObjectPathVariableApplications += 1
                    }
                }

                assertEquals(
                    context(world) {
                        result.registeredResolverApplicationIdentityCounts()
                    },
                    witness.applicationIdentityCounts(),
                )
                assertTrue(
                    context(world) {
                        result.correctResolution(fragment)
                    },
                )
                context(world) {
                    result.validateObjectPathBindings()
                }
                completedCases += 1
            }

        assertEquals(run.expectedCases, run.attemptedCases)
        assertEquals(run.expectedCases, attemptedCases)
        assertEquals(run.expectedCases, resolutionCalls)
        assertEquals(run.expectedCases, completedCases)
        run.assertAggregate(
            observedSignatures.containsAll(requiredSignatures),
            "Resolver26 profile $propertyProfile missed required signatures: " +
                "${requiredSignatures - observedSignatures}; " +
                "observed=$observedSignatures",
        )
        return completedCases
    } finally {
        println(
            "Resolver26 broad stress: profile=$propertyProfile, seed=$seed, " +
                "size=${counts.summary()}, " +
                "attemptedCases=$attemptedCases, resolutionCalls=$resolutionCalls, " +
                "completedCases=$completedCases, " +
                "resolverApplications=$resolverApplications, " +
                "generatedArgumentVariables=$generatedArgumentVariables, " +
                "generatedObjectPathVariables=$generatedObjectPathVariables, " +
                "activatedArgumentVariableApplications=" +
                "$activatedArgumentVariableApplications, " +
                "activatedObjectPathVariableApplications=" +
                "$activatedObjectPathVariableApplications, " +
                "maximumProviderPathLength=$maximumProviderPathLength, " +
                "maximumVariableUseDepth=$maximumVariableUseDepth, " +
                "signatures=$observedSignatures, " +
                "elapsedMillis=${(System.nanoTime() - startedAt) / 1_000_000}",
        )
    }
}

// Returns compact S:R:Q dimensions for diagnostics.
private fun TestCaseCount.summary(): String =
    "$schemas:$registriesPerSchema:$queriesPerSchema"
