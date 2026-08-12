package semantics.resolver25

import kotlinx.coroutines.runBlocking
import model.fragmentFrom
import model.objectOf
import org.junit.jupiter.api.Test
import semantics.arbitrary.Config
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ExplicitFieldResolverWeight
import semantics.arbitrary.FieldArgumentWeight
import semantics.arbitrary.InputScalarValueRange
import semantics.arbitrary.ListTypeWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.MinimumSelectionDepth
import semantics.arbitrary.NodeObjectWeight
import semantics.arbitrary.NullValueWeight
import semantics.arbitrary.NullableTypeWeight
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.QueryFieldCount
import semantics.arbitrary.ResolverArgumentErrorWeight
import semantics.arbitrary.ResolverFragmentDepth
import semantics.arbitrary.ResolverFragmentWeight
import semantics.arbitrary.ResolverFragmentsEnabled
import semantics.arbitrary.ResolverFromArgumentVariablesEnabled
import semantics.arbitrary.ResolverFromObjectFieldPassiveUseWeight
import semantics.arbitrary.ResolverFromObjectFieldProviderPathLength
import semantics.arbitrary.ResolverFromObjectFieldVariableOwnerLimit
import semantics.arbitrary.ResolverFromObjectFieldVariableOwnerUseWeight
import semantics.arbitrary.ResolverFromObjectFieldVariableUseDepth
import semantics.arbitrary.ResolverLiteralVariableConvergenceWeight
import semantics.arbitrary.ResolverNestedProviderPathWeight
import semantics.arbitrary.ResolverVariableCount
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.ResolverVariablesEnabled
import semantics.arbitrary.RootQueryFieldCount
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
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
    fun `balanced full-feature worlds resolve correctly`(): Unit =
        runBlocking {
            val counts = configuredCounts()
            val seed = configuredSeed()
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

            try {
                val run =
                    checkResolverTestCases(
                        counts = counts,
                        config = BROAD_CONFIG,
                        profile = PROFILE,
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

                        val world = testWorld.newAssumptions(selectiveResolvers = true)
                        val fragment = world.fragmentFrom(testCase.query.source)
                        testCase.registry.clearResolutionWitness()
                        resolutionCalls += 1
                        val result =
                            resolveWithLifecycleValidation(
                                world = world,
                                root = world.objectOf("Query"),
                                selections = fragment.subselections,
                            )
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

                        assertEquals(
                            context(world) {
                                result.registeredResolverApplicationIdentityCounts()
                            },
                            witness.applicationIdentityCounts(),
                        )
                        assertTrue(context(world) { result.correctResolution(fragment) })
                        context(world) {
                            result.validateObjectPathBindings()
                        }
                        completedCases += 1
                    }

                assertEquals(run.expectedCases, run.attemptedCases)
                assertEquals(run.expectedCases, attemptedCases)
                assertEquals(run.expectedCases, resolutionCalls)
                assertEquals(run.expectedCases, completedCases)
            } finally {
                println(
                    "Resolver25 broad stress: seed=$seed, size=${counts.summary()}, " +
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
                        "elapsedMillis=${(System.nanoTime() - startedAt) / 1_000_000}",
                )
            }
        }

    private fun configuredCounts(): TestCaseCount {
        val configured =
            System.getProperty(SIZE_PROPERTY)
                ?: System.getenv(SIZE_ENVIRONMENT)
                ?: System.getProperty("resolver.property.size")
                ?: DEFAULT_SIZE
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
        const val PROFILE = "resolver25-broad-stress"
        const val SIZE_PROPERTY = "resolver25.broad.stress.size"
        const val SIZE_ENVIRONMENT = "RESOLVER25_BROAD_STRESS_SIZE"
        const val SEED_PROPERTY = "resolver25.broad.stress.seed"
        const val SEED_ENVIRONMENT = "RESOLVER25_BROAD_STRESS_SEED"
        const val DEFAULT_SIZE = "10:20:50"

        val BROAD_CONFIG: Config =
            Config.default +
                (MinimumSelectionDepth to 2) +
                (MaxSelectionDepth to 4) +
                (SchemaObjectCount to 5..7) +
                (ObjectFieldCount to 4..6) +
                (QueryFieldCount to 6..8) +
                (RootQueryFieldCount to 4..6) +
                (DuplicateSelectionWeight to 0.2) +
                (FieldArgumentWeight to 0.65) +
                (ExplicitFieldResolverWeight to 0.8) +
                (InputScalarValueRange to 0..4) +
                (ListTypeWeight to 0.25) +
                (ListValueSize to 0..2) +
                (NullableTypeWeight to 0.35) +
                (NullValueWeight to 0.15) +
                (ErrorValueWeight to 0.08) +
                (NodeObjectWeight to 0.2) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFragmentWeight to 0.8) +
                (ResolverFragmentDepth to 3) +
                (ResolverArgumentErrorWeight to 0.05) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariablesEnabled to true) +
                (ResolverVariableWeight to 0.65) +
                (ResolverVariableCount to 1..3) +
                (ResolverLiteralVariableConvergenceWeight to 0.2) +
                (ResolverNestedProviderPathWeight to 0.5) +
                (ResolverFromObjectFieldProviderPathLength to 1..3) +
                (ResolverFromObjectFieldVariableUseDepth to 1..3) +
                (ResolverFromObjectFieldVariableOwnerLimit to 4) +
                (ResolverFromObjectFieldPassiveUseWeight to 0.25) +
                (ResolverFromObjectFieldVariableOwnerUseWeight to 0.25)
    }
}
