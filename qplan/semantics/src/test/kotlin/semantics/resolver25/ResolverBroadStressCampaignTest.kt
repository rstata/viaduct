package semantics.resolver25

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import semantics.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import kotlin.test.assertEquals

class ResolverBroadStressCampaignTest {
    @Test
    fun `campaign round resolves every case in five broad profiles`(): Unit =
        runBlocking {
            val campaignRound = Resolver25BroadStressCampaign.round(configuredRound())
            val selectedProfile = configuredProfile()
            val configuredCase = System.getProperty(RESOLVER_TEST_CASE_PROPERTY)
            require(
                configuredCase == null ||
                    configuredCase.equals("all", ignoreCase = true) ||
                    selectedProfile != null,
            ) {
                "A campaign coordinate replay must select $PROFILE_PROPERTY"
            }
            val runs =
                campaignRound.runs.filter { run ->
                    selectedProfile == null || run.profile == selectedProfile
                }
            var completedCases = 0
            runs.forEach { run ->
                completedCases +=
                    runResolver25BroadStress(
                        profile = run.propertyProfile,
                        counts = run.counts,
                        config = run.config,
                        seed = run.seed,
                    )
            }
            val expectedCases =
                if (
                    configuredCase != null &&
                    !configuredCase.equals("all", ignoreCase = true)
                ) {
                    runs.size
                } else {
                    runs.sumOf(Resolver25BroadStressCampaignRun::expectedCases)
                }
            assertEquals(expectedCases, completedCases)
            println(
                "Resolver25 broad campaign round ${campaignRound.number}: " +
                    "phase=${campaignRound.phase.id}, profiles=${runs.size}, " +
                    "completedCases=$completedCases",
            )
        }

    private fun configuredRound(): Int =
        System
            .getProperty(Resolver25BroadStressCampaign.ROUND_PROPERTY)
            ?.toIntOrNull()
            ?: error("Set ${Resolver25BroadStressCampaign.ROUND_PROPERTY} to a manifest round")

    private fun configuredProfile(): Resolver25BroadStressProfile? =
        System
            .getProperty(Resolver25BroadStressCampaign.PROFILE_PROPERTY)
            ?.let(Resolver25BroadStressProfile::fromConfigured)

    private companion object {
        const val PROFILE_PROPERTY = Resolver25BroadStressCampaign.PROFILE_PROPERTY
    }
}
