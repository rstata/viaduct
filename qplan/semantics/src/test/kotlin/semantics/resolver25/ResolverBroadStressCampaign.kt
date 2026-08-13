package semantics.resolver25

import semantics.arbitrary.Config
import semantics.arbitrary.TestCaseCount

internal data class Resolver25BroadStressCampaignRound(
    val number: Int,
    val baseSeed: Long,
    val phase: Resolver25BroadStressCampaignPhase,
) {
    val runs: List<Resolver25BroadStressCampaignRun>
        get() =
            Resolver25BroadStressProfile.entries.mapIndexed { index, profile ->
                Resolver25BroadStressCampaignRun(
                    round = number,
                    phase = phase,
                    profile = profile,
                    propertyProfile =
                        "resolver25-broad-campaign-v1-r${number.toString().padStart(3, '0')}-" +
                            profile.id,
                    counts =
                        if (profile == Resolver25BroadStressProfile.MULTIPLE_OWNERS) {
                            phase.multipleOwnerCounts
                        } else {
                            phase.commonCounts
                        },
                    config =
                        if (phase.largeDeep) {
                            profile.config.withLargeDeepResolver25Worlds()
                        } else {
                            profile.config
                        },
                    seed = Math.addExact(Math.multiplyExact(baseSeed, 10), index.toLong() + 1),
                )
            }
}

internal data class Resolver25BroadStressCampaignRun(
    val round: Int,
    val phase: Resolver25BroadStressCampaignPhase,
    val profile: Resolver25BroadStressProfile,
    val propertyProfile: String,
    val counts: TestCaseCount,
    val config: Config,
    val seed: Long,
) {
    val expectedCases: Int =
        counts.schemas * counts.registriesPerSchema * counts.queriesPerSchema
}

internal enum class Resolver25BroadStressCampaignPhase(
    val id: String,
    val commonCounts: TestCaseCount,
    val multipleOwnerCounts: TestCaseCount,
    val largeDeep: Boolean,
) {
    SCHEMA_BREADTH(
        id = "schema-breadth",
        commonCounts = TestCaseCount(200, 2, 5),
        multipleOwnerCounts = TestCaseCount(200, 2, 5),
        largeDeep = false,
    ),
    REGISTRY_DIVERSITY(
        id = "registry-diversity",
        commonCounts = TestCaseCount(40, 25, 2),
        multipleOwnerCounts = TestCaseCount(40, 25, 2),
        largeDeep = false,
    ),
    QUERY_INTERACTIONS(
        id = "query-interactions",
        commonCounts = TestCaseCount(10, 4, 50),
        multipleOwnerCounts = TestCaseCount(40, 25, 2),
        largeDeep = false,
    ),
    LARGE_DEEP(
        id = "large-deep",
        commonCounts = TestCaseCount(20, 10, 10),
        multipleOwnerCounts = TestCaseCount(40, 25, 2),
        largeDeep = true,
    ),
    ;

    companion object {
        fun fromId(id: String): Resolver25BroadStressCampaignPhase =
            entries.singleOrNull { phase -> phase.id == id }
                ?: error(
                    "Unknown Resolver25 broad stress campaign phase $id; phases=" +
                        entries.joinToString { phase -> phase.id },
                )
    }
}

internal object Resolver25BroadStressCampaign {
    const val ROUND_PROPERTY = "resolver25.broad.campaign.round"
    const val PROFILE_PROPERTY = "resolver25.broad.campaign.profile"
    const val MANIFEST_RESOURCE =
        "/semantics/resolver25/resolver25-broad-stress-campaign.tsv"

    val rounds: List<Resolver25BroadStressCampaignRound> by lazy {
        val stream =
            requireNotNull(javaClass.getResourceAsStream(MANIFEST_RESOURCE)) {
                "Missing Resolver25 broad stress campaign manifest $MANIFEST_RESOURCE"
            }
        stream.bufferedReader().useLines { lines ->
            lines
                .filterNot { line -> line.isBlank() || line.startsWith("#") }
                .map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 3) {
                        "Campaign rows must have round, seed, and phase columns: $line"
                    }
                    Resolver25BroadStressCampaignRound(
                        number =
                            columns[0].toIntOrNull()
                                ?: error("Campaign round must be an integer: $line"),
                        baseSeed =
                            columns[1].toLongOrNull()
                                ?: error("Campaign seed must be a Long: $line"),
                        phase = Resolver25BroadStressCampaignPhase.fromId(columns[2]),
                    )
                }.toList()
        }
    }

    fun round(number: Int): Resolver25BroadStressCampaignRound =
        rounds.singleOrNull { round -> round.number == number }
            ?: error("Unknown Resolver25 broad stress campaign round $number")
}
