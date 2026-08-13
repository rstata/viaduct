package semantics.resolver25

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import semantics.arbitrary.ErrorValueWeight
import semantics.arbitrary.ListTypeWeight
import semantics.arbitrary.ListValueSize
import semantics.arbitrary.MaxSelectionDepth
import semantics.arbitrary.ObjectFieldCount
import semantics.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import semantics.arbitrary.RESOLVER_TEST_PROFILE_PROPERTY
import semantics.arbitrary.ResolverFromObjectFieldVariableOwnerLimit
import semantics.arbitrary.ResolverFromObjectFieldVariableOwnerUseWeight
import semantics.arbitrary.ResolverLiteralVariableConvergenceWeight
import semantics.arbitrary.SchemaObjectCount
import semantics.arbitrary.TestCaseCount
import semantics.arbitrary.checkResolverTestCases
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverBroadStressCampaignConfigurationTest {
    @Test
    fun `campaign manifest records one million diverse executions`() {
        val rounds = Resolver25BroadStressCampaign.rounds
        val runs = rounds.flatMap(Resolver25BroadStressCampaignRound::runs)

        assertEquals((1..100).toList(), rounds.map { round -> round.number })
        assertEquals(100, rounds.map { round -> round.baseSeed }.toSet().size)
        assertEquals(500, runs.size)
        assertEquals(500, runs.map { run -> run.seed }.toSet().size)
        assertEquals(500, runs.map { run -> run.propertyProfile }.toSet().size)
        assertTrue(runs.all { run -> run.expectedCases == 2_000 })
        assertEquals(1_000_000, runs.sumOf(Resolver25BroadStressCampaignRun::expectedCases))
        assertEquals(
            mapOf(
                Resolver25BroadStressCampaignPhase.SCHEMA_BREADTH to 20,
                Resolver25BroadStressCampaignPhase.REGISTRY_DIVERSITY to 25,
                Resolver25BroadStressCampaignPhase.QUERY_INTERACTIONS to 35,
                Resolver25BroadStressCampaignPhase.LARGE_DEEP to 20,
            ),
            rounds.groupingBy { round -> round.phase }.eachCount(),
        )
        assertTrue(
            rounds
                .filter { round -> round.number > 45 }
                .all { round ->
                    round.runs
                        .single { run ->
                            run.profile == Resolver25BroadStressProfile.MULTIPLE_OWNERS
                        }.counts == Resolver25BroadStressCampaignPhase.REGISTRY_DIVERSITY.multipleOwnerCounts
                },
        )
    }

    @Test
    fun `broad profile knobs exert distinct pressure`() {
        val balanced = Resolver25BroadStressProfile.BALANCED.config
        val listDescendants = Resolver25BroadStressProfile.LIST_DESCENDANTS.config
        val nullableErrors = Resolver25BroadStressProfile.NULLABLE_ERRORS.config
        val mixedVariables = Resolver25BroadStressProfile.MIXED_VARIABLES.config
        val multipleOwners = Resolver25BroadStressProfile.MULTIPLE_OWNERS.config

        assertTrue(listDescendants[ListTypeWeight] > balanced[ListTypeWeight])
        assertEquals(1..2, listDescendants[ListValueSize])
        assertTrue(nullableErrors[ErrorValueWeight] > balanced[ErrorValueWeight])
        assertTrue(
            mixedVariables[ResolverLiteralVariableConvergenceWeight] >
                balanced[ResolverLiteralVariableConvergenceWeight],
        )
        assertTrue(
            multipleOwners[ResolverFromObjectFieldVariableOwnerUseWeight] >
                balanced[ResolverFromObjectFieldVariableOwnerUseWeight],
        )
        assertEquals(4, multipleOwners[ResolverFromObjectFieldVariableOwnerLimit])

        val largeDeep = balanced.withLargeDeepResolver25Worlds()
        assertEquals(8..12, largeDeep[SchemaObjectCount])
        assertEquals(6..10, largeDeep[ObjectFieldCount])
        assertEquals(6, largeDeep[MaxSelectionDepth])
        assertEquals(1..1, largeDeep[ListValueSize])
    }

    @Test
    fun `multiple passive variable branches generate an acyclic registry`() =
        runBlocking {
            val profile = Resolver25BroadStressProfile.NULLABLE_ERRORS
            withSystemProperties(
                RESOLVER_TEST_CASE_PROPERTY to "18:4:1",
                RESOLVER_TEST_PROFILE_PROPERTY to profile.propertyProfile,
            ) {
                val run =
                    checkResolverTestCases(
                        counts =
                            TestCaseCount(
                                schemas = 40,
                                registriesPerSchema = 25,
                                queriesPerSchema = 2,
                            ),
                        config = profile.config,
                        profile = profile.propertyProfile,
                        seed = 2_026_081_200_263L,
                    ) { _, _ -> }

                assertEquals(1, run.attemptedCases)
            }
        }
}

private suspend fun <T> withSystemProperties(
    vararg properties: Pair<String, String>,
    block: suspend () -> T,
): T {
    val previous = properties.associate { (property, _) -> property to System.getProperty(property) }
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
