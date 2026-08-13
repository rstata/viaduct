package semantics.resolver26

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import semantics.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import kotlin.test.assertEquals

class ResolverBroadStressCampaignTest {
    @Test
    fun `campaign round resolves every case in five broad profiles`(): Unit =
        runBlocking {
            val campaignRound: Resolver26BroadStressCampaignRound =
                Resolver26BroadStressCampaign.round(configuredRound())
            val selectedProfile: Resolver26BroadStressProfile? = configuredProfile()
            val configuredCase: String? = System.getProperty(RESOLVER_TEST_CASE_PROPERTY)
            require(
                configuredCase == null ||
                    configuredCase.equals("all", ignoreCase = true) ||
                    selectedProfile != null,
            ) {
                "A campaign coordinate replay must select $PROFILE_PROPERTY"
            }
            val runs: List<Resolver26BroadStressCampaignRun> =
                campaignRound.runs.filter { run ->
                    selectedProfile == null || run.profile == selectedProfile
                }
            var completedCases = 0
            runs.forEach { run ->
                completedCases +=
                    runResolver26BroadStress(
                        broadProfile = run.profile,
                        propertyProfile = run.propertyProfile,
                        counts = run.counts,
                        config = run.config,
                        seed = run.seed,
                    )
            }
            val expectedCases: Int =
                if (
                    configuredCase != null &&
                    !configuredCase.equals("all", ignoreCase = true)
                ) {
                    runs.size
                } else {
                    runs.sumOf(Resolver26BroadStressCampaignRun::expectedCases)
                }
            assertEquals(expectedCases, completedCases)
            println(
                "Resolver26 broad campaign round ${campaignRound.number}: " +
                    "phase=${campaignRound.phase.id}, profiles=${runs.size}, " +
                    "completedCases=$completedCases",
            )
        }

    // Returns the required persisted round number.
    private fun configuredRound(): Int =
        System
            .getProperty(Resolver26BroadStressCampaign.ROUND_PROPERTY)
            ?.toIntOrNull()
            ?: error("Set ${Resolver26BroadStressCampaign.ROUND_PROPERTY} to a manifest round")

    // Returns an optional single profile for focused replay.
    private fun configuredProfile(): Resolver26BroadStressProfile? =
        System
            .getProperty(Resolver26BroadStressCampaign.PROFILE_PROPERTY)
            ?.let(Resolver26BroadStressProfile::fromConfigured)

    private companion object {
        const val PROFILE_PROPERTY = Resolver26BroadStressCampaign.PROFILE_PROPERTY
    }
}
