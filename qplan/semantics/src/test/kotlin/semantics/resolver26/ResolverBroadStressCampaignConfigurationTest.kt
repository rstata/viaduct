package semantics.resolver26

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import semantics.arbitrary.DuplicateSelectionWeight
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.InputScalarValueRange
import semantics.arbitrary.ListTypeWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.ParentFieldsEnabled
import semantics.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import semantics.arbitrary.RESOLVER_TEST_PROFILE_PROPERTY
import semantics.arbitrary.ResolverFromFieldVariableOwnerLimit
import semantics.arbitrary.ResolverFromFieldVariableOwnerUseWeight
import semantics.arbitrary.ResolverFromQueryFieldVariablesEnabled
import semantics.arbitrary.ResolverLiteralVariableConvergenceWeight
import semantics.arbitrary.ResolverQueryFragmentsEnabled
import semantics.arbitrary.ResolverQueryFragmentWeight
import semantics.arbitrary.ResolverTestCaseCoordinate
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.SometimesPassiveFieldWeight
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import semantics.propertytest.PropertyTestCampaignConfigFile
import semantics.propertytest.PropertyTestJson
import semantics.propertytest.PropertyTestRoundExecution
import semantics.propertytest.PropertyTestRoundRunner
import semantics.propertytest.roundConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverBroadStressCampaignConfigurationTest {
    @Test
    fun `campaign manifest records one million heterogeneous executions`() {
        val rounds: List<Resolver26BroadStressCampaignRound> =
            Resolver26BroadStressCampaign.rounds
        val runs: List<Resolver26BroadStressCampaignRun> =
            rounds.flatMap(Resolver26BroadStressCampaignRound::runs)

        assertEquals((1..100).toList(), rounds.map { round -> round.number })
        assertEquals(100, rounds.map { round -> round.baseSeed }.toSet().size)
        assertEquals(500, runs.size)
        assertEquals(500, runs.map { run -> run.seed }.toSet().size)
        assertEquals(500, runs.map { run -> run.propertyProfile }.toSet().size)
        assertTrue(runs.all { run -> run.expectedCases == 2_000 })
        assertEquals(1_000_000, runs.sumOf(Resolver26BroadStressCampaignRun::expectedCases))
        assertEquals(
            mapOf(
                Resolver26BroadStressCampaignPhase.SCHEMA_BREADTH to 20,
                Resolver26BroadStressCampaignPhase.REGISTRY_DIVERSITY to 25,
                Resolver26BroadStressCampaignPhase.QUERY_INTERACTIONS to 35,
                Resolver26BroadStressCampaignPhase.LARGE_DEEP to 20,
            ),
            rounds.groupingBy { round -> round.phase }.eachCount(),
        )
        assertTrue(
            rounds
                .filter { round -> round.number > 45 }
                .all { round ->
                    round.runs
                        .single { run ->
                            run.profile == Resolver26BroadStressProfile.MULTIPLE_OWNERS
                        }.counts ==
                        Resolver26BroadStressCampaignPhase.REGISTRY_DIVERSITY.multipleOwnerCounts
                },
        )
    }

    @Test
    fun `broad profile knobs exert distinct Resolver26 pressure`() {
        val balanced = Resolver26BroadStressProfile.BALANCED.config
        val descendants = Resolver26BroadStressProfile.DESCENDANT_VARIABLES.config
        val nullableErrors = Resolver26BroadStressProfile.NULLABLE_ERRORS.config
        val symbolicIdentity = Resolver26BroadStressProfile.SYMBOLIC_IDENTITY.config
        val multipleOwners = Resolver26BroadStressProfile.MULTIPLE_OWNERS.config

        assertTrue(
            Resolver26BroadStressProfile.entries.all { profile ->
                profile.config[SometimesPassiveFieldWeight] == 0.25
            },
        )
        assertTrue(
            Resolver26BroadStressProfile.entries.all { profile ->
                profile.config[ResolverQueryFragmentsEnabled]
            },
        )
        assertTrue(
            Resolver26BroadStressProfile.entries.all { profile ->
                profile.config[ResolverFromQueryFieldVariablesEnabled]
            },
        )
        assertTrue(
            Resolver26BroadStressProfile.entries.all { profile ->
                profile.config[ResolverQueryFragmentWeight] == 0.1
            },
        )
        assertTrue(
            Resolver26BroadStressProfile.entries.all { profile ->
                profile.config[ParentFieldsEnabled]
            },
        )
        assertTrue(
            Resolver26BroadStressProfile.entries.all { profile ->
                profile.config[MaxSelectionDepth] >= 6
            },
        )

        assertTrue(descendants[ListTypeWeight] > balanced[ListTypeWeight])
        assertEquals(1..2, descendants[ListValueSize])
        assertTrue(nullableErrors[ErrorValueWeight] > balanced[ErrorValueWeight])
        assertTrue(
            symbolicIdentity[ResolverLiteralVariableConvergenceWeight] >
                balanced[ResolverLiteralVariableConvergenceWeight],
        )
        assertEquals(0..1, symbolicIdentity[InputScalarValueRange])
        assertTrue(
            multipleOwners[ResolverFromFieldVariableOwnerUseWeight] >
                balanced[ResolverFromFieldVariableOwnerUseWeight],
        )
        assertEquals(4, multipleOwners[ResolverFromFieldVariableOwnerLimit])

        val largeDeep = balanced.withLargeDeepResolver26Worlds()
        assertEquals(8..12, largeDeep[SchemaObjectCount])
        assertEquals(6..10, largeDeep[ObjectFieldCount])
        assertEquals(6, largeDeep[MaxSelectionDepth])
        assertEquals(
            balanced[DuplicateSelectionWeight],
            largeDeep[DuplicateSelectionWeight],
        )
        assertEquals(1..1, largeDeep[ListValueSize])
    }

    @Test
    fun `campaign records the large deep duplicate-selection cap transition`() {
        val round81Runs: List<Resolver26BroadStressCampaignRun> =
            Resolver26BroadStressCampaign.round(81).runs
        val round95Runs: List<Resolver26BroadStressCampaignRun> =
            Resolver26BroadStressCampaign.round(95).runs

        assertEquals(
            0.1,
            round81Runs
                .single { run ->
                    run.profile == Resolver26BroadStressProfile.SYMBOLIC_IDENTITY
                }.config[DuplicateSelectionWeight],
        )
        assertEquals(
            0.2,
            round81Runs
                .single { run ->
                    run.profile == Resolver26BroadStressProfile.BALANCED
                }.config[DuplicateSelectionWeight],
        )
        assertTrue(
            round95Runs.all { run ->
                run.config[DuplicateSelectionWeight] == 0.1
            },
        )
    }

    @Test
    fun `query interaction rounds retain diversity for registry-shaped profiles`() {
        val queryInteractionRound: Resolver26BroadStressCampaignRound =
            Resolver26BroadStressCampaign.round(46)
        val registryShapedProfiles: Set<Resolver26BroadStressProfile> =
            setOf(
                Resolver26BroadStressProfile.DESCENDANT_VARIABLES,
                Resolver26BroadStressProfile.NULLABLE_ERRORS,
                Resolver26BroadStressProfile.MULTIPLE_OWNERS,
            )
        val registryShapedRuns: List<Resolver26BroadStressCampaignRun> =
            queryInteractionRound.runs.filter { run ->
                run.profile in registryShapedProfiles
            }

        assertTrue(
            registryShapedRuns.all { run ->
                run.counts ==
                    Resolver26BroadStressCampaignPhase.REGISTRY_DIVERSITY.commonCounts
            },
        )
        assertTrue(registryShapedRuns.all { run -> run.expectedCases == 2_000 })
    }

    @Test
    fun `persisted campaign coordinate executes Resolver26 validation oracles`() =
        runBlocking {
            val campaign =
                PropertyTestJson.readResource<PropertyTestCampaignConfigFile>(
                    "/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json",
                )
            val profile = Resolver26BroadStressProfile.BALANCED
            val round = campaign.roundConfig(number = 1, selectedProfileId = profile.id)
            val run = round.runs.single()

            val result =
                PropertyTestRoundRunner.run(
                    round = round,
                    execution =
                        PropertyTestRoundExecution(
                            selectedTestInputProfileId = run.testInputProfileId,
                            selectedCase =
                                ResolverTestCaseCoordinate(
                                    schemaIndex = 1,
                                    registryIndex = 1,
                                    queryIndex = 1,
                                ),
                        ),
                )

            assertEquals(1, result.completedCases)
        }

    @Test
    fun `large deep profiles generate bounded queries`() =
        runBlocking {
            val symbolicIdentityRun: Resolver26BroadStressCampaignRun =
                Resolver26BroadStressCampaign
                    .round(81)
                    .runs
                    .single { run ->
                        run.profile == Resolver26BroadStressProfile.SYMBOLIC_IDENTITY
                    }
            val balancedRun: Resolver26BroadStressCampaignRun =
                Resolver26BroadStressCampaign
                    .round(95)
                    .runs
                    .single { run ->
                        run.profile == Resolver26BroadStressProfile.BALANCED
                    }
            val runCoordinates:
                List<Pair<Resolver26BroadStressCampaignRun, List<String>>> =
                listOf(
                    symbolicIdentityRun to listOf("1:1:1", "12:1:1"),
                    balancedRun to listOf("14:1:1"),
                )

            runCoordinates.forEach { (campaignRun, coordinates) ->
                coordinates.forEach { coordinate ->
                    withSystemProperties(
                        RESOLVER_TEST_CASE_PROPERTY to coordinate,
                        RESOLVER_TEST_PROFILE_PROPERTY to campaignRun.propertyProfile,
                    ) {
                        val run =
                            checkResolverTestCases(
                                counts = campaignRun.counts,
                                config = campaignRun.config,
                                profile = campaignRun.propertyProfile,
                                seed = campaignRun.seed,
                            ) { _, _ -> }

                        assertEquals(1, run.attemptedCases)
                    }
                }
            }
        }

    @Test
    fun `descendant variable generation orders multiple passive branches acyclically`() =
        runBlocking {
            val profile: Resolver26BroadStressProfile =
                Resolver26BroadStressProfile.DESCENDANT_VARIABLES
            withSystemProperties(
                RESOLVER_TEST_CASE_PROPERTY to "48:1:1",
                RESOLVER_TEST_PROFILE_PROPERTY to profile.propertyProfile,
            ) {
                val run =
                    checkResolverTestCases(
                        counts =
                            TestCaseCount(
                                schemas = 200,
                                registriesPerSchema = 2,
                                queriesPerSchema = 5,
                            ),
                        config = profile.config,
                        profile = profile.propertyProfile,
                        seed = 2_026_081_300_022L,
                    ) { _, _ -> }

                assertEquals(1, run.attemptedCases)
            }
        }
}

// Temporarily installs replay coordinates without leaking them into neighboring generated tests.
private suspend fun <T> withSystemProperties(
    vararg properties: Pair<String, String>,
    block: suspend () -> T,
): T {
    val previous: Map<String, String?> =
        properties.associate { (property, _) -> property to System.getProperty(property) }
    return try {
        properties.forEach { (property, value) -> System.setProperty(property, value) }
        block()
    } finally {
        previous.forEach { (property, value) ->
            if (value == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, value)
            }
        }
    }
}
