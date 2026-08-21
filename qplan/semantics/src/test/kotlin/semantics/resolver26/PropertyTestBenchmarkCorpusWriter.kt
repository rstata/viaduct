package semantics.resolver26

import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.Fragment
import model.ObjectEngineResult
import model.fragmentFrom
import semantics.arbitrary.RESOLVER_TEST_CASE_PROPERTY
import semantics.arbitrary.ResolutionWitness
import semantics.arbitrary.checkResolverTestCases
import semantics.arbitrary.encodeResolverBenchmarkCorpus
import semantics.contract.registeredResolverApplicationIdentityCounts
import semantics.contract.validateObjectPathBindings
import semantics.correctresolution.correctResolution
import java.nio.file.Files
import java.nio.file.Path

object PropertyTestBenchmarkCorpusWriter {
    private const val CAMPAIGN_ROUND = 46
    private const val SELECTED_CASE = "10:4:3"
    private const val EXPECTED_RESOLVER_APPLICATIONS = 12_763

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) {
            "Expected arguments: <output-directory>"
        }
        val outputDirectory = Path.of(arguments.single())
        val campaignRun =
            Resolver26BroadStressCampaign
                .round(CAMPAIGN_ROUND)
                .runs
                .single { run ->
                    run.profile == Resolver26BroadStressProfile.STAMP_COLLISIONS
                }
        val previousCase = System.getProperty(RESOLVER_TEST_CASE_PROPERTY)
        System.setProperty(RESOLVER_TEST_CASE_PROPERTY, SELECTED_CASE)
        try {
            runBlocking {
                var captured = false
                checkResolverTestCases(
                    counts = campaignRun.counts,
                    config = campaignRun.config,
                    profile = campaignRun.propertyProfile,
                    seed = campaignRun.seed,
                ) { testWorld, testCase ->
                    val coordinates = requireNotNull(testCase.coordinates)
                    val world: Assumptions =
                        testWorld.newAssumptions(selectiveResolvers = true)
                    val fragment: Fragment = world.fragmentFrom(testCase.query.source)
                    testCase.registry.clearResolutionWitness()
                    val result: ObjectEngineResult =
                        context(world) {
                            resolve(fragment.subselections)
                        }
                    val witness: ResolutionWitness = testCase.registry.resolutionWitness()
                    check(witness.applications.size == EXPECTED_RESOLVER_APPLICATIONS)
                    check(
                        context(world) {
                            result.registeredResolverApplicationIdentityCounts()
                        } == witness.applicationIdentityCounts(),
                    )
                    check(context(world) { result.correctResolution(fragment) })
                    context(world) {
                        result.validateObjectPathBindings()
                    }

                    Files.createDirectories(outputDirectory)
                    Files.writeString(
                        outputDirectory.resolve("schema.graphqls"),
                        testCase.schema.sdl,
                    )
                    Files.writeString(
                        outputDirectory.resolve("registry.json"),
                        testCase.registry.encodeResolverBenchmarkCorpus(
                            schema = testCase.schema,
                            metrics =
                                mapOf(
                                    "campaignBaseSeed" to
                                        Resolver26BroadStressCampaign
                                            .round(CAMPAIGN_ROUND)
                                            .baseSeed,
                                    "campaignRound" to CAMPAIGN_ROUND.toLong(),
                                    "propertySeed" to campaignRun.seed,
                                    "queryIndex" to
                                        coordinates.queryIndex.toLong(),
                                    "registryIndex" to
                                        coordinates.registryIndex.toLong(),
                                    "resolverApplications" to
                                        witness.applications.size.toLong(),
                                    "schemaIndex" to
                                        coordinates.schemaIndex.toLong(),
                                ),
                        ),
                    )
                    Files.writeString(
                        outputDirectory.resolve("query.graphql"),
                        testCase.query.source + System.lineSeparator(),
                    )
                    Files.writeString(
                        outputDirectory.resolve("provenance.txt"),
                        buildString {
                            appendLine("campaignRound=$CAMPAIGN_ROUND")
                            appendLine("profile=${campaignRun.propertyProfile}")
                            appendLine("propertySeed=${campaignRun.seed}")
                            appendLine("campaignSize=${campaignRun.counts.summary()}")
                            appendLine("selectedCase=$SELECTED_CASE")
                            appendLine(
                                "resolverApplications=${witness.applications.size}",
                            )
                        },
                    )
                    captured = true
                }
                check(captured) {
                    "Property-test benchmark case $SELECTED_CASE was not captured"
                }
            }
        } finally {
            if (previousCase == null) {
                System.clearProperty(RESOLVER_TEST_CASE_PROPERTY)
            } else {
                System.setProperty(RESOLVER_TEST_CASE_PROPERTY, previousCase)
            }
        }
        println("Wrote frozen property-test benchmark corpus to $outputDirectory")
    }

    private fun semantics.arbitrary.TestCaseCount.summary(): String =
        "$schemas:$registriesPerSchema:$queriesPerSchema"
}
