package semantics.resolver25

import kotlinx.coroutines.runBlocking
import model.fragmentFrom
import model.objectOf
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverTestExecution
import semantics.arbitrary.SometimesPassiveFieldWeight
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.configuredResolverTestExecution
import semantics.arbitrary.executeResolverTestCases
import semantics.arbitrary.registeredResolverOccurrenceCounts
import semantics.contract.registeredResolverApplicationIdentityCounts
import semantics.contract.validateObjectPathBindings
import semantics.correctresolution.correctResolution
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unfiltered Resolver25 stress: every generated registry/query product is resolved and validated.
 */
class ResolverBroadStressTest {
    @Test
    fun `broad full-feature worlds resolve correctly`(): Unit =
        runBlocking {
            val broadProfile = configuredProfile()
            runResolver25BroadStress(
                profile = broadProfile.propertyProfile,
                counts = configuredCounts(broadProfile),
                config = broadProfile.config,
                seed = configuredSeed(),
            )
        }

    private fun configuredProfile(): Resolver25BroadStressProfile {
        val configured =
            System.getProperty(PROFILE_PROPERTY)
                ?: System.getenv(PROFILE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.profile")
                ?: Resolver25BroadStressProfile.BALANCED.propertyProfile
        return Resolver25BroadStressProfile.fromConfigured(configured)
    }

    private fun configuredCounts(
        broadProfile: Resolver25BroadStressProfile,
    ): TestCaseCount {
        val configured =
            System.getProperty(SIZE_PROPERTY)
                ?: System.getenv(SIZE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.size")
                ?: broadProfile.defaultSize
        val dimensions = configured.split(':')
        require(dimensions.size == 3) {
            "$SIZE_PROPERTY/$SIZE_ENVIRONMENT must have S:R:Q form: $configured"
        }
        val parsed =
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

    private fun configuredSeed(): Long {
        val configured =
            System.getProperty(SEED_PROPERTY)
                ?: System.getenv(SEED_ENVIRONMENT)
                ?: System.getProperty("resolver.property.seed")
                ?: error(
                    "Set $SEED_PROPERTY or $SEED_ENVIRONMENT; use the " +
                        ":semantics:resolver25BroadStress task",
                )
        return configured.toLongOrNull()
            ?: error("$SEED_PROPERTY/$SEED_ENVIRONMENT must be a Long: $configured")
    }

    private fun TestCaseCount.summary(): String =
        "$schemas:$registriesPerSchema:$queriesPerSchema"

    private companion object {
        const val PROFILE_PROPERTY = "resolver25.broad.stress.profile"
        const val PROFILE_ENVIRONMENT = "RESOLVER25_BROAD_STRESS_PROFILE"
        const val SIZE_PROPERTY = "resolver25.broad.stress.size"
        const val SIZE_ENVIRONMENT = "RESOLVER25_BROAD_STRESS_SIZE"
        const val SEED_PROPERTY = "resolver25.broad.stress.seed"
        const val SEED_ENVIRONMENT = "RESOLVER25_BROAD_STRESS_SEED"
    }
}

internal suspend fun runResolver25BroadStress(
    profile: String,
    counts: TestCaseCount,
    config: Config,
    seed: Long,
    execution: ResolverTestExecution = configuredResolverTestExecution(counts, profile),
): Int {
    val startedAt = System.nanoTime()
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
    var generatedSometimesPassiveFields = 0
    var activatedSometimesPassiveOccurrences = 0

    try {
        val run =
            executeResolverTestCases(
                execution = execution,
                config = config,
                profile = profile,
                seed = seed,
            ) { testWorld, testCase ->
                attemptedCases += 1
                generatedArgumentVariables +=
                    testCase.registry.features.fromArgumentVariableCount
                generatedObjectPathVariables +=
                    testCase.registry.features.fromObjectFieldVariableCount
                generatedSometimesPassiveFields +=
                    testCase.registry.features.sometimesPassiveFieldCount
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

                val world = testWorld.newAssumptions(selectiveResolvers = true)
                val fragment = world.fragmentFrom(testCase.query.source)
                testCase.registry.clearResolutionWitness()
                resolutionCalls += 1
                val observation =
                    observeWithLifecycleValidation(
                        world = world,
                        root = world.objectOf("Query"),
                        selections = fragment.subselections,
                    )
                val result = observation.result
                val witness = testCase.registry.resolutionWitness()
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

                if (config[SometimesPassiveFieldWeight] > 0.0) {
                    val resultOccurrenceCounts =
                        context(world) {
                            result.registeredResolverOccurrenceCounts(world.resolverRegistry)
                        }
                    witness.applicationCounts().forEach { (key, count) ->
                        assertTrue(
                            count <= resultOccurrenceCounts.getOrDefault(key, 0),
                            "Observed $count applications of $key but result contains only " +
                                "${resultOccurrenceCounts.getOrDefault(key, 0)} occurrences",
                        )
                    }
                    activatedSometimesPassiveOccurrences +=
                        resultOccurrenceCounts.values.sum() - witness.applications.size
                } else {
                    assertEquals(
                        context(world) {
                            result.registeredResolverApplicationIdentityCounts()
                        },
                        witness.applicationIdentityCounts(),
                    )
                }
                assertTrue(context(world) { result.correctResolution(fragment) })
                context(world) {
                    result.validateObjectPathBindings(observation.appliedResolverOccurrences)
                }
                completedCases += 1
            }

        assertEquals(run.expectedCases, run.attemptedCases)
        assertEquals(run.expectedCases, attemptedCases)
        assertEquals(run.expectedCases, resolutionCalls)
        assertEquals(run.expectedCases, completedCases)
        if (config[SometimesPassiveFieldWeight] > 0.0) {
            run.assertAggregate(
                generatedSometimesPassiveFields > 0 &&
                    activatedSometimesPassiveOccurrences > 0,
                "Resolver25 profile $profile did not activate sometimes-passive fields",
            )
        }
        return completedCases
    } finally {
        println(
            "Resolver25 broad stress: profile=$profile, seed=$seed, " +
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
                "generatedSometimesPassiveFields=$generatedSometimesPassiveFields, " +
                "activatedSometimesPassiveOccurrences=" +
                "$activatedSometimesPassiveOccurrences, " +
                "elapsedMillis=${(System.nanoTime() - startedAt) / 1_000_000}",
        )
    }
}

private fun TestCaseCount.summary(): String =
    "$schemas:$registriesPerSchema:$queriesPerSchema"
