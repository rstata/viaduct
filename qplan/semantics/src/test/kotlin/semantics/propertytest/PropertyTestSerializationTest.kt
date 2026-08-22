package semantics.propertytest

import semantics.arbitrary.Config
import semantics.arbitrary.GeneratorConfigData
import semantics.arbitrary.ResolverVariableWeight
import semantics.arbitrary.TestCaseCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertyTestSerializationTest {
    @Test
    fun `launcher layer serializes generator data`() {
        val data = GeneratorConfigData.from("default", Config.default)

        val decoded =
            PropertyTestJson.read<GeneratorConfigData>(
                PropertyTestJson.write(data),
            )

        assertEquals(data, decoded)
    }

    @Test
    fun `layered generator document upserts resolver values before binding data class`() {
        val defaults = GeneratorConfigData.from("unused", Config.default)
        val document =
            GeneratorConfigDocumentFile(
                formatVersion = GENERATOR_CONFIG_DOCUMENT_FORMAT_VERSION,
                shared = defaults.valuesData(),
                resolver25 =
                    GeneratorConfigProfileData(
                        id = "resolver25-layered",
                        doubles = mapOf(ResolverVariableWeight.wireName to 0.75),
                    ),
            )

        val data = generatorConfigDataFromDocument(document, "test")

        assertEquals(listOf("resolver25-layered"), data.map(GeneratorConfigData::id))
        assertEquals(0.75, data.single().toConfig()[ResolverVariableWeight])
    }

    @Test
    fun `launcher layer serializes round configuration`() {
        val round =
            PropertyTestRoundConfigFile(
                formatVersion = PROPERTY_TEST_ROUND_FORMAT_VERSION,
                id = "sample-round",
                runs =
                    listOf(
                        PropertyTestRunConfig(
                            subjectProfileId = RESOLVER26_BROAD_CORRECTNESS_SUBJECT_ID,
                            testInputProfileId = "resolver26-balanced-standard",
                            seed = 42,
                            counts = TestCaseCount(2, 3, 4),
                            requiredCoverage = setOf("nested-variable-use"),
                        ),
                    ),
            )

        val decoded =
            PropertyTestJson.read<PropertyTestRoundConfigFile>(
                PropertyTestJson.write(round),
            )

        assertEquals(round, decoded)
        assertTrue(PropertyTestJson.write(round).endsWith("\n"))
    }

    @Test
    fun `launcher layer serializes compact campaign ranges`() {
        val campaign =
            PropertyTestCampaignConfigFile(
                formatVersion = PROPERTY_TEST_CAMPAIGN_FORMAT_VERSION,
                id = "sample-campaign",
                subjectProfileId = RESOLVER25_BROAD_CORRECTNESS_SUBJECT_ID,
                seedMultiplier = 10,
                profiles =
                    listOf(
                        PropertyTestCampaignProfileConfig(
                            id = "sample",
                            seedOffset = 1,
                            testInputProfiles = mapOf("standard" to "sample-input"),
                        ),
                    ),
                phases =
                    mapOf(
                        "standard" to
                            PropertyTestCampaignPhaseConfig(
                                testInputVariant = "standard",
                                counts = TestCaseCount(2, 3, 4),
                            ),
                    ),
                rounds =
                    listOf(
                        PropertyTestCampaignRoundRangeConfig(
                            first = 2,
                            last = 3,
                            baseSeed = 100,
                            phaseId = "standard",
                        ),
                    ),
            )

        val decoded =
            PropertyTestJson.read<PropertyTestCampaignConfigFile>(
                PropertyTestJson.write(campaign),
            )

        assertEquals(campaign, decoded)
        assertEquals(listOf(2, 3), decoded.roundNumbers())
        assertEquals(1011, decoded.roundConfig(3).runs.single().seed)
    }
}
